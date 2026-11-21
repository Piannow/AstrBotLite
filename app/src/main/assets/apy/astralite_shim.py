# -*- coding: utf-8 -*-
"""
astralite_shim — AstrBotLite 统一 Python 环境的垫片（1.7.55 触发修复版）。

相对旧版的关键修复（对应“市场插件无法触发”实测）：
  A. zip 带顶层目录（如 astrbot_plugin_xxx-1.2.3/main.py）也能找到入口——
     依次尝试 根目录 main.py/plugin.py → 单层子目录 main.py/plugin.py；
  B. 模块级 @command/@filter.command 装饰的函数也会被收集进注册表
     （旧版只扫 @register 的类，模块级命令永远不触发）；
  C. async def 处理器：协程自动 await（旧版返回 coroutine 对象字符串）；
  D. 签名自适应调用（event/arg 两种习惯都试，取第一个不抛的）；
  E. 导入失败不再静默：result 带 import_errors，Kotlin 侧记日志；
  F. 更多 stub 模块（astrbot.filter / astrbot.api.event.filter / astrbot.core.base …）；
  G. dispatch 支持第 4 参数 configs_json：插件页保存的配置注入 context.config，
     插件内 context.get_plugin_config(...) 可读到真实配置。

调用约定：
  dispatch(ids_json, text, session, configs_json="") ->
      {"ok":"true","reply":...} / {"ok":"false","error":...,"import_errors":{pid:msg}}
"""

import asyncio
import base64
import concurrent.futures
import importlib
import importlib.util
import inspect
import json
import os
import re
import sys
import threading
import time
import traceback
import urllib.error
import urllib.parse
import urllib.request

# ---------------------------------------------------------------- 日志桥（1.7.105）
# 垫片/插件的 print 只进 logcat，App 日志查看器看不到（设备侧黑盒根因之一）。
# 所有输出同时进本队列，随 call_web_api/dispatch 响应带回，Kotlin 侧逐条落 Logs。
_PLUGIN_LOGS = []
_PLUGIN_LOGS_MAX = 200


def _capture_log(msg):
    try:
        _PLUGIN_LOGS.append(str(msg)[:400])
        if len(_PLUGIN_LOGS) > _PLUGIN_LOGS_MAX:
            del _PLUGIN_LOGS[:_PLUGIN_LOGS_MAX // 2]
    except Exception:
        pass


def _drain_logs_json():
    if not _PLUGIN_LOGS:
        return None
    out = list(_PLUGIN_LOGS)
    _PLUGIN_LOGS.clear()
    return out


# ---------------------------------------------------------------- 全局注册表
_REGISTRY = {}          # pluginId -> {"main": module, "commands": {name: handler}, "aliases": {alias: name}, "import_error": str}
_LOADED = set()         # 已成功导入的 pluginId

# ---------------------------------------------------------------- 日志 & 事件 stub
class _Logger:
    def _p(self, m, *a):
        try:
            text = m if not a else (str(m) + " " + " ".join(str(x) for x in a))
            print("[astrbot-lite]", text)
            _capture_log(text)  # 1.7.105 日志桥：进 App 日志查看器
        except Exception:
            pass
    def info(self, *a, **k): self._p(*a)
    def error(self, *a, **k): self._p(*a)
    def warning(self, *a, **k): self._p(*a)
    def debug(self, *a, **k): self._p(*a)

logger = _Logger()

class _Sender:
    def __init__(self, name):
        self.id = 0
        self.name = name
        self.member_card = name
        self.nickname = name

class _Session:
    def __init__(self, sid):
        self.id = 0
        self.name = sid
        self.is_group = False

class _Event:
    """最小事件对象：兼容 event.message/pure_str/unified_msg_origin 等常见属性"""
    def __init__(self, text, sender="user", session=""):
        self.message = text
        self.text = text
        self.raw_message = text
        self.pure_str = text
        self.message_str = text
        self.sender = _Sender(sender)
        self.session = _Session(session)
        self.unified_msg_origin = session or "astrbot_lite"
        self.platform = "astrbot_lite"
        self.images = []
        self.image_urls = []
        self.reply_msg_id = ""
        self.is_admin = False
        self.msg_id = ""
        self._stopped = False
        # 原始字段字典（event.get 优先从这里取，如 post_type/message_type）
        self.raw = {}

    def get(self, key, default=None, *a, **k):
        """1.7.256 修复：原来恒返回 None，插件里 event.get("post_type") 之类分支全部静默失灵。
        先查 raw_message 原始字典，再按常见字段推导，最后回落到 default。"""
        raw = getattr(self, "raw", None) or {}
        if key in raw:
            return raw.get(key)
        if key == "post_type":
            return "message"
        if key == "message_type":
            return "group" if getattr(self.session, "id", 0) else "private"
        if key in ("self_id", "bot_id"):
            return 0
        if key == "user_id":
            return getattr(self.sender, "id", 0)
        if key == "group_id":
            return getattr(self.session, "id", 0)
        if key == "message":
            return self.text
        if key == "raw_message":
            return self.raw_message
        if key == "sender":
            return self.sender
        if key == "time":
            return int(time.time())
        return default

    # ---- 结果构造（event.plain_result / chain_result，插件回复出口） ----
    def plain_result(self, text, *a, **k):
        return _Result("text", str(text))

    def chain_result(self, chain, *a, **k):
        """消息链：拆出文本 / 图片 / 按钮行（ActionRow），供 Kotlin 分别发送或降级成纯文本"""
        texts, images, rows = [], [], []
        try:
            for item in (chain or []):
                if isinstance(item, _ActionRow):
                    r = _qq_button_rows(item)
                    if r:
                        rows.append(r)
                elif isinstance(item, _Plain):
                    texts.append(item.text)
                elif isinstance(item, _At):
                    texts.append("@" + str(getattr(item, "qq", "")))
                elif isinstance(item, _Image):
                    images.append(str(item.url))
                elif hasattr(item, "text"):
                    texts.append(str(item.text))
                else:
                    u = getattr(item, "url", None) or getattr(item, "file", None)
                    if u:
                        images.append(str(u))
            return _Result("chain", {"text": "".join(texts), "images": images, "rows": rows[:5]})
        except Exception:
            return _Result("text", str(chain))

    def stop_event(self, *a, **k):
        self._stopped = True

    def is_stopped(self):
        return self._stopped

    # ---- 常用读取 ----
    def get_message_str(self, *a, **k):
        return str(self.text or "")

    def get_plain_str(self, *a, **k):
        return str(self.text or "")

    def get_sender_id(self, *a, **k):
        return getattr(self.sender, "id", 0)

    def get_group_id(self, *a, **k):
        return getattr(self.session, "id", 0)

    def get_at(self, *a, **k):
        return []

    async def send(self, *a, **k):
        return None

    def __getattr__(self, n):
        if n.startswith("__"):
            raise AttributeError(n)
        return _any_stub_type("event." + n)

class _Result:
    def __init__(self, kind, payload):
        self.kind = kind
        self.payload = payload

def plain_result(text, *a, **k):
    return _Result("text", text)

def image_result(url, *a, **k):
    return _Result("image", url)

class _AgentFacade:
    """ctx.agent（AstrBot 4.30+ 新 Agent 运行时）。
    Lite 没有可复用的 Agent turn，这里提供可调用占位：插件调用 ctx.agent.run_turn()
    不会 AttributeError/中断整条消息，只是不产生额外回复。"""
    def run_turn(self, *a, **k):
        return None
    async def run_turn_async(self, *a, **k):
        return None
    def __getattr__(self, n):
        if n.startswith("__"):
            raise AttributeError(n)
        return lambda *a, **k: None

class _PluginCtx:
    """register 装饰器目标 + 配置上下文：dispatch 时注入 context.config = {key:value}"""
    config = {}
    agent = _AgentFacade()
    @property
    def plugin_manager(self): return self
    def get_plugin_config(self, name, default=""):
        return self.config.get(name, default)
    def register_web_api(self, route, view_handler, methods=None, desc=""):
        """插件 Web API 注册（对齐官方 context.register_web_api）"""
        _register_web_api(route, view_handler, methods, desc)

    async def llm_generate(self, **kwargs):
        """context.llm_generate（对齐官方 4.5.7）：走 Lite AI 桥（set_ai_bridge 注入的配置）"""
        return await _llm_generate_impl(**kwargs)

context = _PluginCtx()

class Plugin:
    def __init__(self, *a, **k):
        pass

class PluginConfig:
    def __init__(self, *a, **k):
        pass

# ---------------------------------------------------------------- register / command
_current_plugin_id = None

def _command(name, *a, **k):
    """命令装饰器：打标，稍后统一收集"""
    def deco(fn):
        fn._astr_cmd = name
        return fn
    return deco

def _register(name=None, author=None, desc=None, version=None, required_astralcore_version=None, **kwargs):
    """类注册装饰器：实例化类（尽量），收集带 _astr_cmd 的方法（含基类）"""
    def deco(cls):
        pid = _current_plugin_id or getattr(cls, "__module__", "unknown")
        reg = _REGISTRY.setdefault(pid, {"main": None, "commands": {}, "aliases": {}, "import_error": ""})
        # 实例化：兼容各类构造签名——AstrBot 4.x 常见 (context, config)，旧式 (context) / ()。
        # __init__ 里的 context.register_web_api 等副作用只有实例化成功才会执行（1.7.88 修复根因）。
        inst = None
        # 传 _CtxConfig（带 save_config）：插件 __init__ 常 self.config = config 原样覆盖，
        # 裸 dict 会让插件的 save_config() 调用报 RuntimeError（1.7.88）
        cfg = _CtxConfig(pid, _pending_config(pid))
        for args in ((context, cfg), (context,), (cfg,), (), (None, cfg), (None, None), (None,)):
            try:
                inst = cls(*args)
                break
            except TypeError:
                continue  # 签名不匹配，换下一种
            except Exception:
                logger.error("插件 %s 实例化异常: %s" % (pid, traceback.format_exc(limit=2)))
                break  # 签名匹配但内部出错：不再尝试其它签名（避免重复副作用）
        reg["inst"] = inst
        if inst is None:
            logger.warning("插件 %s 实例化失败（构造签名不兼容），命令/注册表可能不完整" % pid)
        for base in getattr(cls, "__mro__", (cls,)):
            for hname in dir(base):
                if hname.startswith("__"):
                    continue
                try:
                    attr = getattr(base, hname)
                except Exception:
                    continue
                cmdname = getattr(attr, "_astr_cmd", None)
                if not cmdname or not callable(attr):
                    continue
                handler = getattr(inst, hname, attr) if inst is not None else attr
                reg["commands"].setdefault(cmdname, handler)
                for alias in getattr(attr, "_astr_alias", []) or []:
                    reg["aliases"][alias] = cmdname
        return cls
    return deco

def _filter_command(name, *a, **k):
    """旧式 filter.command：打标（含别名），模块级/类级都靠收集器捡回。
    1.7.256 修复：原来忽略 *a，@filter.command("a","b") 的别名 b 永远进不了注册表。
    1.7.262：补齐上游关键字写法 alias={"x"} / aliases=["x"] 与 priority 等参数。"""
    def deco(fn):
        fn._astr_cmd = name
        extra = [str(x) for x in a if x]
        kw_alias = k.get("alias", k.get("aliases"))
        if kw_alias:
            if isinstance(kw_alias, (set, list, tuple)):
                extra += [str(x) for x in kw_alias if x]
            else:
                extra.append(str(kw_alias))
        if extra:
            fn._astr_alias = extra
        return fn
    return deco

class _GroupFn:
    """命令组装饰结果：可当默认 handler 调用（转发原函数），且支持 .command("子") 链式注册。
    对齐 AstrBot filter.command_group 语义（1.7.88）。"""
    def __init__(self, fn, group):
        self._fn = fn
        self._group = str(group or "").strip()
        if self._group:
            self._astr_cmd = self._group   # 组头本身进注册表（无参时命中）

    def __call__(self, *a, **k):
        return self._fn(*a, **k)

    def command(self, sub, *aliases, **k):
        full = ("%s %s" % (self._group, str(sub).strip())).strip()
        group_fn = self

        def deco(fn):
            fn._astr_cmd = full
            extra = [("%s %s" % (group_fn._group, a)).strip() for a in aliases if a]
            kw_alias = k.get("alias", k.get("aliases"))
            if kw_alias:
                _aliases = kw_alias if isinstance(kw_alias, (set, list, tuple)) else [kw_alias]
                extra += [("%s %s" % (group_fn._group, str(a))).strip() for a in _aliases if a]
            if extra:
                fn._astr_alias = extra
            return fn
        return deco

    def __getattr__(self, n):
        if n.startswith("__"):
            raise AttributeError(n)
        return _any_stub_type(str(self._group) + "." + n)


def _command_group(group=None, *a, **k):
    def deco(fn):
        return _GroupFn(fn, group)
    return deco


def _regex(pattern, *a, **k):
    """filter.regex：正则触发（打 _astr_regex 标，dispatch 命中即分发）"""
    def deco(fn):
        fn._astr_regex = str(pattern)
        return fn
    return deco


def _permission_type(*a, **k):
    """filter.permission_type：权限装饰（垫片不鉴权，原样返回保留内层打标）"""
    def deco(fn):
        return fn
    return deco

def _event_message_type(*a, **k):
    """filter.event_message_type：消息监听（上游 4.x 常用）。
    垫片把它标成“事件处理器”，dispatch 在无命令命中时统一触发。"""
    def deco(fn):
        fn._astr_event = True
        return fn
    return deco

def _platform_adapter_type(*a, **k):
    """filter.platform_adapter_type：平台过滤（Lite 只有 QQ，原样放行）"""
    def deco(fn):
        return fn
    return deco

def _custom_filter(*a, **k):
    """filter.custom_filter / 未知过滤器：原样放行，避免插件 import 期 AttributeError"""
    def deco(fn):
        return fn
    return deco

def _button_action(action_id=None, *a, **k):
    """filter.button_action("插件:按钮ID")：QQ/Telegram 等平台点击按钮后的回调入口。
    不传 ID 时匹配所有按钮回调（对齐上游）。"""
    def deco(fn):
        fn._astr_button_action = str(action_id) if action_id else "*"
        return fn
    return deco


def _on_plugin_loaded(*a, **k):
    """filter.on_plugin_loaded：插件加载完成钩子（_import_plugin 末尾统一触发）"""
    def deco(fn):
        fn._astr_on_loaded = True
        return fn
    return deco


class _FilterMod:
    command = staticmethod(_filter_command)
    async_command = staticmethod(_filter_command)
    command_group = staticmethod(_command_group)
    regex = staticmethod(_regex)
    permission_type = staticmethod(_permission_type)
    event_message_type = staticmethod(_event_message_type)
    platform_adapter_type = staticmethod(_platform_adapter_type)
    custom_filter = staticmethod(_custom_filter)
    button_action = staticmethod(_button_action)
    on_plugin_loaded = staticmethod(_on_plugin_loaded)
    # LLM/消息 hook 在模块底部后置绑定（_hook_factory 定义在其后）

# ---------------------------------------------------------------- 插件 Web API（视图后端，1.7.87）
# 对齐 AstrBot 官方 astrbot/api/web.py 语义（轻量兼容版）：
#   context.register_web_api(route, handler, methods, desc) 注册；
#   handler 内 from astrbot.api.web import request / json_response / error_response ...；
#   call_web_api() 由 Kotlin 桥（PluginWebUiActivity → PythonRuntime）调用。
_WEB_APIS = []  # [(route, handler, methods(list), desc, pid)]

class _MultiDict:
    """官方 PluginMultiDict 兼容：get 取最后一个同名值，type 转换失败回 default"""
    def __init__(self, pairs):
        self._pairs = list(pairs or [])

    def get(self, key, default=None, type=None):
        for k, v in reversed(self._pairs):
            if k != key:
                continue
            if type is None:
                return v
            try:
                return type(v)
            except Exception:
                return default
        return default

    def getlist(self, key):
        return [v for k, v in self._pairs if k == key]

    def keys(self):
        out = []
        for k, _ in self._pairs:
            if k not in out:
                out.append(k)
        return out

    def items(self):
        return list(self._pairs)

    def __contains__(self, key):
        return any(k == key for k, _ in self._pairs)

    def __getitem__(self, key):
        for k, v in reversed(self._pairs):
            if k == key:
                return v
        raise KeyError(key)

    def __bool__(self):
        return bool(self._pairs)


class _UploadFile:
    """官方 PluginUploadFile 兼容（内存缓冲版）：filename/content_type + async save/read/..."""
    def __init__(self, filename, content_type, data, field_name="file"):
        self.filename = filename or "upload.bin"
        self.content_type = content_type or "application/octet-stream"
        self.headers = {}
        self.content_length = len(data)
        self.field_name = field_name
        self._data = data
        self._pos = 0

    async def read(self, size=-1):
        if size is None or size < 0:
            size = len(self._data) - self._pos
        chunk = self._data[self._pos:self._pos + size]
        self._pos += len(chunk)
        return chunk

    async def write(self, data):
        self._data += data
        self.content_length = len(self._data)

    async def seek(self, offset):
        self._pos = max(0, int(offset))

    async def close(self):
        pass

    async def save(self, destination, max_bytes=None):
        if max_bytes is not None and len(self._data) > max_bytes:
            raise ValueError("upload exceeds max_bytes")
        with open(str(destination), "wb") as f:
            f.write(self._data)
        return len(self._data)


class _CtxReq:
    """当前 Web 请求（thread-local 绑定，仅 handler 执行期间可读）"""
    _local = threading.local()

    def __init__(self, method, path, plugin_name, username, headers, cookies,
                 content_type, path_params, query_pairs, body_bytes, form_pairs, file_objs):
        self.method = method
        self.path = path
        self.plugin_name = plugin_name
        self.username = username
        self.headers = headers or {}
        self.cookies = cookies or {}
        self.content_type = content_type
        self.client_host = "local"
        self.path_params = path_params or {}
        self.query = _MultiDict(query_pairs)
        # Quart 兼容面（1.7.102 实证：插件 plugin_api 用 from quart import request）
        # request.args 即 query（.get/.getlist 语义一致）；get_json 同步读 body
        self.args = self.query
        self._body = body_bytes or b""
        self._form = _MultiDict(form_pairs)
        self._files = _MultiDict(file_objs)

    async def body(self):
        return self._body

    async def json(self, default=None):
        try:
            if not self._body:
                return default
            return json.loads(self._body.decode("utf-8", "replace"))
        except Exception:
            return default

    def get_json(self, silent=False, force=False, cache=True):
        """Quart/Flask request.get_json() 形态：同步可读 + 可 await 双形态（_AwaitableDict）"""
        if not self._body:
            return _AwaitableDict()
        try:
            v = json.loads(self._body.decode("utf-8", "replace"))
            return _AwaitableDict(v) if isinstance(v, dict) else (v if v is not None else _AwaitableDict())
        except Exception:
            if silent:
                return _AwaitableDict()
            raise

    async def form(self):
        return self._form

    async def files(self):
        return self._files


class _ReqProxy:
    """模块级 request 代理：属性/方法转发到当前线程绑定的 _CtxReq"""
    def _cur(self):
        r = getattr(_CtxReq._local, "req", None)
        if r is None:
            raise RuntimeError("astrbot.api.web.request 仅在插件 Web API handler 执行期间可用")
        return r

    def __getattr__(self, name):
        return getattr(self._cur(), name)


request = _ReqProxy()


class _WebResp:
    """handler 返回值包装：json/file/stream 三类（file/stream 本地桥暂不支持，明确报错）"""
    def __init__(self, kind, body, status_code=200, headers=None, filename=None, content_type=None):
        self.kind = kind
        self.body = body
        self.status_code = status_code
        self.headers = headers or {}
        self.filename = filename
        self.content_type = content_type


def _json_response(data=None, *, status_code=200, headers=None):
    return _WebResp("json", {} if data is None else data, status_code, headers)


def _error_response(message, *, status_code=400, data=None, headers=None):
    return _json_response({"status": "error", "message": message, "data": data},
                          status_code=status_code, headers=headers)


def _file_response(path, *, filename=None, content_type=None, headers=None):
    return _WebResp("file", str(path), 200, headers, filename, content_type or "application/octet-stream")


def _stream_response(content, *, content_type="text/event-stream", status_code=200, headers=None):
    return _WebResp("stream", content, status_code, headers, None, content_type)


def _jsonify(*args, **kwargs):
    """quart.jsonify 兼容：jsonify(dict) / jsonify(*args)"""
    payload = args[0] if len(args) == 1 else (list(args) if args else None)
    return _json_response(payload)


class _StarBase:
    """astrbot.api.star.Star / AstrBotPlugin 基类：兼容 (context)/(context, config) 构造签名。
    self.context 恒为全局 context（register_web_api 写全局注册表）。"""
    def __init__(self, ctx=None, config=None, *a, **k):
        self.context = context
        self.config = _CtxConfig(
            _current_plugin_id or "",
            dict(config) if isinstance(config, dict) else {}
        )


class _Image:
    """astrbot.api.all.Image：消息图片占位（from_url/from_file/fromFileSystem 返回实例）"""
    def __init__(self, *a, **k):
        self.url = a[0] if a else k.get("url", "")

    @staticmethod
    def from_url(u=None, *a, **k):
        return _Image(u)

    @staticmethod
    def from_file(p=None, *a, **k):
        return _Image(p)

    @staticmethod
    def fromFileSystem(p=None, *a, **k):
        return _Image(p)

    def __getattr__(self, n):
        if n.startswith("__"):
            raise AttributeError(n)
        return _any_stub_type("Image." + n)


class _Plain:
    """astrbot.api.message_components.Plain：文本组件"""
    def __init__(self, text="", *a, **k):
        self.text = str(text)

class _At:
    """astrbot.api.message_components.At（QQ 官方文本里 @ 用纯文本近似）"""
    def __init__(self, qq="", *a, **k):
        self.qq = qq

class _Button:
    """astrbot.api.message_components.Button（AstrBot 4.x 交互按钮）"""
    class Style:
        PRIMARY = 1
        SECONDARY = 0
        SUCCESS = 2
        DANGER = 3
        LINK = 0

    class CallbackAction:
        def __init__(self, data=None, **k):
            self.data = data

    class UrlAction:
        def __init__(self, url="", **k):
            self.url = url

    def __init__(self, id="", label="", action=None, style=None, *a, **k):
        self.id = str(id or "")
        self.label = str(label or "")
        self.action = action
        self.style = style

class _ActionRow:
    """astrbot.api.message_components.ActionRow：一行按钮 + 不支持平台的回退文案"""
    def __init__(self, buttons=None, action_timeout=None, fallback_text="", *a, **k):
        self.buttons = list(buttons or [])
        self.fallback_text = str(fallback_text or "")

def _qq_button_rows(row):
    """把 ActionRow 转成 QQ 官方 keyboard.content.rows 结构（最多 5 行 × 5 按钮）"""
    btns = []
    for b in (row.buttons or [])[:5]:
        act = getattr(b, "action", None)
        label = getattr(b, "label", "") or "按钮"
        if isinstance(act, _Button.UrlAction):
            action = {
                "type": 0,
                "permission": {"type": 2},
                "data": str(getattr(act, "url", "") or ""),
                "unsupport_tips": row.fallback_text or "请升级 QQ 后使用按钮",
            }
        else:
            data = act.data if isinstance(act, _Button.CallbackAction) else {}
            try:
                data_str = json.dumps(data, ensure_ascii=False)
            except Exception:
                data_str = str(data)
            action = {
                "type": 1,
                "permission": {"type": 2},
                "data": data_str,
                "unsupport_tips": row.fallback_text or "请升级 QQ 后使用按钮",
            }
        btns.append({
            "id": getattr(b, "id", "") or str(len(btns) + 1),
            "render_data": {
                "label": label,
                "visited_label": label,
                "style": 1 if getattr(b, "style", 0) == _Button.Style.PRIMARY else 0,
            },
            "action": action,
        })
    return {"buttons": btns} if btns else None


class _StarTools:
    """astrbot.core.star.star_tools.StarTools：get_data_dir 落到 apy/_data/{name}（持久化）"""
    @staticmethod
    def get_data_dir(name):
        d = os.path.join(
            os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
            "_data", str(name).replace(":", "_")
        )
        try:
            os.makedirs(d, exist_ok=True)
        except Exception:
            pass
        return d

    @staticmethod
    def get_data_file(name, rel):
        return os.path.join(_StarTools.get_data_dir(name), str(rel))


class _GreedyStr:
    """astrbot.core.star.filter.command.GreedyStr：类型哨兵（作注解/默认值，不实例化）"""
    pass


def _any_stub_type(name):
    """未知符号兜底：可实例化、可继承、可链式属性访问、可当装饰器的万能类。
    类属性访问经元类 __getattr__（command_group(...).command 这类链式用法不炸）。"""
    def _getattr_meta(cls, n):
        if n.startswith("__"):
            raise AttributeError(n)
        return _any_stub_type(str(cls.__name__) + "." + n)

    def _init(self, *a, **k):
        pass

    def _getattr_inst(self, n):
        if n.startswith("__"):
            raise AttributeError(n)
        return _any_stub_type(type(self).__name__ + "." + n)

    def _call_inst(self, *a, **k):
        # 装饰器形态：@stub(...) 或 @(stub实例) → 原样返回被装饰对象
        if len(a) == 1 and not k and callable(a[0]):
            try:
                return a[0]
            except Exception:
                pass
        return _any_stub_type(str(name) + "()")

    safe = "".join(ch for ch in str(name) if ch.isalnum())[:24] or "Stub"
    meta = type("M" + safe, (type,), {"__getattr__": _getattr_meta})
    return meta(safe, (object,), {
        "__init__": _init,
        "__getattr__": _getattr_inst,
        "__call__": _call_inst,
    })


_STUB_LOGGED = set()


def _module_getattr_factory(mod_name):
    """模块级 __getattr__（PEP 562）：未知符号返回万能类，陌生插件 import 不再中断"""
    def _gm(name):
        if name.startswith("__"):
            raise AttributeError(name)
        # 1.7.242：同一符号只提示一次。插件每条消息都会反复取这些符号，
        # 原来每次都打 INFO，日志会被 "[stub] ... 未实现" 刷屏（用户实测一屏全是它）。
        key = "%s.%s" % (mod_name, name)
        if key not in _STUB_LOGGED:
            _STUB_LOGGED.add(key)
            logger.info("[stub] %s 未实现，返回占位类（后续不再重复提示）" % key)
        else:
            logger.debug("[stub] %s 未实现" % key)
        return _any_stub_type(name)
    return _gm


class _StubAstLoader:
    """astrbot.* 未知子模块的万能加载器（配合 _StubAstFinder）"""
    def create_module(self, spec):
        import types as _t
        m = _t.ModuleType(spec.name)
        m.__path__ = []   # 标记为包：可继续作父包
        m.__getattr__ = _module_getattr_factory(spec.name)
        return m

    def exec_module(self, module):
        pass


class _StubAstFinder:
    """sys.meta_path 兜底：任意未显式注册的 astrbot.* 模块动态创建为万能包。
    解决真实插件 import 未覆盖子模块（如 astrbot.api.message_components）时
    "'astrbot.api' is not a package"/ModuleNotFoundError 中断插件加载的问题。"""
    def find_spec(self, fullname, path=None, target=None):
        if fullname == "astrbot" or fullname.startswith("astrbot."):
            if fullname in sys.modules:
                return None
            import importlib.machinery
            return importlib.machinery.ModuleSpec(fullname, _StubAstLoader(), is_package=True)
        return None


_finder_installed = False


def _install_meta_finder():
    global _finder_installed
    if _finder_installed:
        return
    _finder_installed = True
    try:
        sys.meta_path.insert(0, _StubAstFinder())
    except Exception:
        pass


# ---------------------------------------------------------------- LLM 桥（1.7.88：插件自带 LLM）
# Kotlin 侧把 Lite AI 池的对话配置注入这里；插件 context.llm_generate 直连 OpenAI 兼容接口。
_AI_BRIDGE = {"enabled": False, "baseUrl": "", "apiKey": "", "model": ""}


def set_ai_bridge(cfg_json):
    """PythonRuntime 注入 Lite AI 配置（每次对话前同步，配置改动即时生效）"""
    global _AI_BRIDGE
    try:
        d = json.loads(cfg_json or "{}")
        _AI_BRIDGE = {
            "enabled": bool(d.get("enabled")),
            "baseUrl": str(d.get("baseUrl") or "").strip(),
            "apiKey": str(d.get("apiKey") or "").strip(),
            "model": str(d.get("model") or "").strip(),
        }
        return "true"
    except Exception:
        return "false"


class _LLMResp:
    """LLMResponse 兼容对象：公开 role / completion_text / tools_call_name
    （真实插件消费面：getattr(response,"role")、completion_text、tools_call_name）"""
    def __init__(self, text, role="assistant"):
        self.role = role
        self.completion_text = text
        self.tools_call_name = None
        self.result = text

    def __getattr__(self, n):
        if n.startswith("__"):
            raise AttributeError(n)
        return None  # 未知字段探测返回 None（比万能类更符合响应语义）


async def _llm_generate_impl(*, chat_provider_id=None, prompt=None, image_urls=None,
                             audio_urls=None, tools=None, system_prompt=None,
                             contexts=None, **kwargs):
    """context.llm_generate（对齐官方 4.5.7 签名）：走 Lite AI 池注入的 OpenAI 兼容配置。
    chat_provider_id 在 Lite 无 provider 体系——忽略并用桥配置；未配置时抛错（插件有 fallback）。"""
    if not _AI_BRIDGE.get("enabled") or not _AI_BRIDGE.get("baseUrl"):
        raise RuntimeError("本地桥：未配置 AI 对话（设置→API 配置或 AI 池分配对话条目）")
    msgs = []
    if system_prompt:
        msgs.append({"role": "system", "content": str(system_prompt)})
    for c in (contexts or []):
        try:
            if isinstance(c, dict):
                msgs.append({"role": str(c.get("role") or "user"), "content": str(c.get("content") or "")})
                continue
            role = str(getattr(c, "role", None) or "user")
            content = getattr(c, "content", None)
            if content is None:
                content = c
            if isinstance(content, list):  # Message.parts → 拼文本
                parts = []
                for pt in content:
                    t = getattr(pt, "text", None)
                    if t: parts.append(str(t))
                    elif isinstance(pt, str): parts.append(pt)
                content = "\n".join(parts)
            msgs.append({"role": role, "content": str(content)})
        except Exception:
            continue
    if prompt is not None:
        msgs.append({"role": "user", "content": str(prompt)})
    if not msgs:
        raise RuntimeError("本地桥：llm_generate 无可用消息")
    payload = {"model": _AI_BRIDGE["model"], "messages": msgs}
    # OpenAI 兼容透传（max_tokens/temperature/top_p 等）
    for k in ("max_tokens", "temperature", "top_p", "presence_penalty", "frequency_penalty"):
        if k in kwargs and kwargs[k] is not None:
            payload[k] = kwargs[k]
    # 1.7.256 修复：tools 原来收了参数却从不放进请求体（tools_call_name 永远 None）
    if tools:
        payload["tools"] = tools
        tc_choice = kwargs.get("tool_choice")
        payload["tool_choice"] = tc_choice if tc_choice else "auto"
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    headers = {"Content-Type": "application/json"}
    if _AI_BRIDGE["apiKey"]:
        headers["Authorization"] = "Bearer " + _AI_BRIDGE["apiKey"]
    url = _AI_BRIDGE["baseUrl"].rstrip("/") + ("/chat/completions" if not _AI_BRIDGE["baseUrl"].rstrip("/").endswith("/chat/completions") else "")
    req = urllib.request.Request(url, data=body, headers=headers, method="POST")

    def _call():
        """1.7.256：429/5xx/网络异常重试 3 次；非 JSON 响应给出响应片段而不是直接抛解析栈。"""
        last_err = None
        for attempt in range(3):
            try:
                with urllib.request.urlopen(req, timeout=60) as resp:
                    raw = resp.read().decode("utf-8", "replace")
                try:
                    return json.loads(raw)
                except ValueError:
                    last_err = RuntimeError("本地桥：LLM 非 JSON 响应：%s" % raw[:160])
            except urllib.error.HTTPError as e:
                snippet = ""
                try:
                    snippet = e.read().decode("utf-8", "replace")[:160]
                except Exception:
                    pass
                last_err = RuntimeError("本地桥：HTTP %s %s" % (e.code, snippet))
                if not (e.code == 429 or 500 <= e.code < 600):
                    raise last_err
            except Exception as e:
                last_err = e
            if attempt < 2:
                time.sleep(1.0 if attempt == 0 else 2.0)
        raise last_err if last_err else RuntimeError("本地桥：LLM 请求失败")

    loop = asyncio.get_event_loop()
    data = await loop.run_in_executor(None, _call)  # 网络 IO 不阻塞事件循环
    choices = data.get("choices") or []
    if not choices:
        raise RuntimeError("本地桥：LLM 返回无 choices（%s）" % str(data.get("error") or data)[:160])
    ch = choices[0]
    msg = ch.get("message") or {}
    text = msg.get("content")
    if text is None:
        text = ch.get("text") or ""
    resp_obj = _LLMResp(str(text or ""), str(msg.get("role") or "assistant"))
    # 1.7.256：解析工具调用（tool_calls），填 tools_call_name / tools_call_args
    tcs = msg.get("tool_calls") or []
    if tcs:
        fn = (tcs[0].get("function") or {})
        resp_obj.tool_calls = tcs
        resp_obj.tools_call_name = fn.get("name")
        try:
            resp_obj.tools_call_args = json.loads(fn.get("arguments") or "{}")
        except Exception:
            resp_obj.tools_call_args = fn.get("arguments")
    return resp_obj


def _plugin_ctx_llm_generate(self, **kwargs):
    """绑定到 _PluginCtx 的 async 包装（插件侧 await context.llm_generate(...)）"""
    return _llm_generate_impl(**kwargs)


# ---------------------------------------------------------------- Hook 体系（1.7.88：on_llm_request/on_llm_response 等）
_HOOK_KINDS = ("on_llm_request", "on_llm_response", "on_decorating_result",
               "on_waiting_llm_request", "on_agent_begin", "on_agent_done",
               "on_using_llm_tool", "on_llm_tool_respond",
               "after_message_sent", "on_plugin_error")


def _hook_factory(kind):
    """filter.on_llm_request() 等 hook 装饰器工厂：打 _astr_hook 标（收集进 hook_kinds）"""
    def deco(*a, **k):
        def d(fn):
            fn._astr_hook = kind
            return fn
        # 支持两种形态：@filter.on_llm_request() 与 @filter.on_llm_request
        if len(a) == 1 and not k and callable(a[0]):
            return d(a[0])
        return d
    return deco


# _FilterMod 类体定义早于本处（模块级后置绑定 hook 装饰器）
for _hk in _HOOK_KINDS:
    setattr(_FilterMod, _hk, staticmethod(_hook_factory(_hk)))
_FilterMod.llm_tool = staticmethod(lambda *a, **k: (lambda fn: fn))


class _HookEvent(_Event):
    """hook 事件：可改写 messages（LLM 请求）或 text（LLM 响应）；
    兼容 event 常用读取（message/pure_str/sender/session）。"""
    def __init__(self, payload):
        super().__init__(str(payload.get("text") or ""), session=str(payload.get("session") or ""))
        self.messages = [dict(m) for m in (payload.get("msgs") or []) if isinstance(m, dict)]
        self.result_text = str(payload.get("text") or "")

    def export(self):
        out = {"session": self.session}
        if self.messages:
            out["msgs"] = self.messages
        new_text = self.result_text if self.result_text != self.text else self.text
        out["text"] = new_text
        return out


def run_plugin_hooks(kind, payload_json):
    """Kotlin 接线入口：对全部已加载（并按 ids 确保 import）的插件跑指定 kind 的 hook。
    payload: {"ids":[...], "text":..., "session":..., "msgs":[{role,content}...]}
    返回: {"ok":"true","payload":{改写后}}；hook 异常逐个隔离不影响其它插件。"""
    try:
        payload = json.loads(payload_json or "{}")
    except Exception:
        payload = {}
    ids = payload.get("ids") or []
    import_errors = {}
    for pid in ids:
        try:
            base = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), pid)
            _import_plugin(pid, base)
            reg = _REGISTRY.get(pid) or {}
            if reg.get("import_error"):
                import_errors[pid] = str(reg["import_error"])[:200]
        except Exception:
            pass
    ev = _HookEvent(payload)
    ran = 0
    for pid in ids:
        reg = _REGISTRY.get(pid) or {}
        for h in list((reg.get("hook_kinds") or {}).get(kind, [])):
            try:
                for call in _attempts_for(h, ev, ""):
                    try:
                        _await_if_needed(call())
                        ran += 1
                        break
                    except TypeError:
                        continue
            except Exception:
                logger.warning("[hook] %s %s 执行失败: %s" % (pid, kind, traceback.format_exc(limit=1)))
    out = {"ok": "true", "payload": ev.export(), "ran": ran}
    if import_errors:
        out["import_errors"] = import_errors
    return json.dumps(out, ensure_ascii=False, default=str)


class _AwaitableDict(dict):
    """既可同步读（dict 行为）又可 `await`（插件两种写法都兼容：payload = request.get_json()
    / payload = await request.get_json()——1.7.102 实证 POST 接口批量炸 'await dict'）"""
    def __await__(self):
        async def _self():
            return self
        return _self().__await__()


def _register_web_api(route, view_handler, methods=None, desc=""):
    """context.register_web_api（对齐官方 register_web_api）：同 route+methods 替换，全局注册表"""
    try:
        ms = [str(m).upper() for m in (methods or ["GET"])]
        rt = str(route)
        for i, api in enumerate(_WEB_APIS):
            if api[0] == rt and api[2] == ms:
                _WEB_APIS[i] = (rt, view_handler, ms, str(desc or ""), _current_plugin_id or "")
                return
        _WEB_APIS.append((rt, view_handler, ms, str(desc or ""), _current_plugin_id or ""))
        logger.debug("[web] 注册 API: %s %s" % (",".join(ms), rt))
    except Exception:
        logger.error("register_web_api 失败: %s" % traceback.format_exc(limit=2))


def _route_regex(route):
    """/p/items/<id> → ^/p/items/(?P<id>[^/]+)$；<path:x> → (?P<x>.*)"""
    parts = []
    pos = 0
    for m in re.finditer(r"<(?:path:)?([A-Za-z_][A-Za-z0-9_]*)>", route):
        parts.append(re.escape(route[pos:m.start()]))
        if m.group(0).startswith("<path:"):
            parts.append("(?P<%s>.*)" % m.group(1))
        else:
            parts.append("(?P<%s>[^/]+)" % m.group(1))
        pos = m.end()
    parts.append(re.escape(route[pos:]))
    return re.compile("^" + "".join(parts) + "$")


def _coerce(v):
    """存储的字符串配置值 → Python 真类型（"true"→True、"[1,2]"→list、"3"→3）。
    保守策略：前导零数字保持字符串（"007" 不变），JSON 解析失败保持原串。"""
    if not isinstance(v, str):
        return v
    s = v.strip()
    if s == "true":
        return True
    if s == "false":
        return False
    if s[:1] in ("[", "{"):
        try:
            return json.loads(s)
        except Exception:
            pass
    if re.fullmatch(r"-?\d+", s):
        try:
            if str(int(s)) == s:
                return int(s)
        except Exception:
            pass
    if re.fullmatch(r"-?\d+\.\d+", s):
        try:
            if str(float(s)) == s:
                return float(s)
        except Exception:
            pass
    return v


def _expand_nested(flat):
    """扁平 "组.子" 键 → 嵌套 dict（1.7.89）：插件按 AstrBot 4.x 结构读 config[组][子]。
    无点号的键原样保留（兼容旧扁平配置与非分组字段）。"""
    out = {}
    for k, v in (flat or {}).items():
        ks = str(k)
        if "." in ks:
            group, _, sub = ks.partition(".")
            g = out.setdefault(group, {})
            if isinstance(g, dict):
                g[sub] = v
        else:
            out[ks] = v
    return out


def _cfg_to_stored(d):
    """Python 配置值 → 存储字符串（写回 plugin.json config 的格式）。
    1.7.89：嵌套 dict 递归展平为 "组.子" 键（与展开转换的 key 形态对齐，roundtrip 一致）。"""
    out = {}

    def walk(prefix, obj):
        for k, v in obj.items():
            key = (prefix + "." + str(k)) if prefix else str(k)
            if isinstance(v, dict):
                walk(key, v)
            elif isinstance(v, bool):
                out[key] = "true" if v else "false"
            elif isinstance(v, list):
                out[key] = json.dumps(v, ensure_ascii=False)
            elif v is None:
                out[key] = ""
            else:
                out[key] = str(v)

    walk("", d or {})
    return out


class _CtxConfig(dict):
    """插件配置容器（AstrBotConfig 兼容：dict + save_config()）。
    save_config() 或直接改键都会被 call_web_api 检测到，变更随响应写回 Lite 存储。"""
    def __init__(self, pid, data=None):
        super().__init__()
        self._pid = pid
        self._dirty = False
        for k, v in (data or {}).items():
            super().__setitem__(k, v)

    def __setitem__(self, k, v):
        self._dirty = True
        super().__setitem__(k, v)

    def save_config(self, *a, **k):
        self._dirty = True

    def pop(self, *a, **k):
        self._dirty = True
        return super().pop(*a, **k)


class _ABConfig(_CtxConfig):
    """AstrBotConfig 官方语义 = **dict 子类**（插件源码注释实证："AstrBotConfig is a dict
    subclass with runtime locks"）。旧 stub 是空类 → 插件迭代 AstrBotConfig(...) 时
    'object is not iterable' 直接崩（1.7.109 真机实证 companion 全部 /page API 失败根因）。
    构造兼容：AstrBotConfig(dict) / AstrBotConfig() / AstrBotConfig(**kwargs)。"""
    def __init__(self, data=None, **kwargs):
        merged = {}
        if isinstance(data, dict):
            merged.update(data)
        merged.update(kwargs)
        super().__init__("", merged)


# 实例化前由 dispatch/call_web_api 预置：pid -> coerce 后配置（构造器 config 参数用）
_PENDING_CFG = {}


def _pending_config(pid):
    # 存储为扁平 "组.子"，出参展开为嵌套（插件按 AstrBot 4.x 结构读）
    return _expand_nested(dict(_PENDING_CFG.get(pid) or {}))


def _refresh_instance_config(pid, cfg):
    """把最新配置刷进已实例化插件的 self.config（保持引用；dict.__setitem__ 绕过脏标记）。"""
    inst = (_REGISTRY.get(pid) or {}).get("inst")
    if inst is None:
        return
    c = getattr(inst, "config", None)
    if isinstance(c, dict):
        c.clear()
        for k, v in (cfg or {}).items():
            dict.__setitem__(c, k, v)
        if isinstance(c, _CtxConfig):
            c._dirty = False
    else:
        inst.config = _CtxConfig(pid, dict(cfg or {}))


def _match_web_api(path, method):
    """匹配注册表：先全量（route 含插件名前缀）→ 再双方剥第一段（id≠包名兼容）。
    返回 (handler, path_values, pid)；不中返回 (None, None, None)"""
    for route, handler, methods, desc, pid in _WEB_APIS:
        if method.upper() not in methods:
            continue
        m = _route_regex(route).fullmatch(path)
        if m:
            return handler, m.groupdict(), pid
    # 兜底：路由/请求双方都剥掉第一段（插件 id 与注册包名不一致时）
    if path.count("/") >= 2:
        p2 = "/" + path.lstrip("/").split("/", 1)[1]
        for route, handler, methods, desc, pid in _WEB_APIS:
            if method.upper() not in methods:
                continue
            r = route if route.count("/") < 2 else "/" + route.lstrip("/").split("/", 1)[1]
            m = _route_regex(r).fullmatch(p2)
            if m:
                return handler, m.groupdict(), pid
    return None, None, None


def _resp_to_wire(r):
    """handler 返回值 → 桥接线格式 {ok, payload, status} / {ok:false, error}"""
    try:
        if isinstance(r, _WebResp):
            if r.kind == "json":
                b = r.body
                # 官方 bridge 规则：{status:"error"} 或 HTTP>=400 一律 reject
                if isinstance(b, dict) and b.get("status") == "error":
                    return {"ok": "false", "error": str(b.get("message") or "接口错误")}
                if int(r.status_code) >= 400:
                    err = b if not isinstance(b, (dict, list)) else json.dumps(b, ensure_ascii=False)
                    return {"ok": "false", "error": str(err)}
                return {"ok": "true", "payload": b, "status": int(r.status_code)}
            if r.kind == "file":
                return {"ok": "false", "error": "本地版暂不支持文件下载（bridge.download）"}
            if r.kind == "stream":
                return {"ok": "false", "error": "本地版暂不支持 SSE（bridge.subscribeSSE）"}
        if isinstance(r, tuple) and len(r) == 2:
            body, code = r
            try:
                code = int(code)
            except Exception:
                code = 200
            # 1.7.89+：(json_response(...), status) 元组——body 是 _WebResp 时递归合并状态，
            # 否则 error 会变成对象地址字符串（实证 6 个接口错误信息不可读）
            if isinstance(body, _WebResp):
                try:
                    body.status_code = code
                except Exception:
                    pass
                return _resp_to_wire(body)
            if isinstance(body, dict) and body.get("status") == "error":
                return {"ok": "false", "error": str(body.get("message") or "接口错误")}
            if code >= 400:
                return {"ok": "false", "error": str(body)}
            return {"ok": "true", "payload": body, "status": code}
        if isinstance(r, dict) and r.get("status") == "error":
            return {"ok": "false", "error": str(r.get("message") or "接口错误")}
        if r is None:
            return {"ok": "true", "payload": None, "status": 200}
        if isinstance(r, (dict, list, str, int, float, bool)):
            return {"ok": "true", "payload": r, "status": 200}
        return {"ok": "true", "payload": str(r), "status": 200}
    except Exception as e:
        return {"ok": "false", "error": "响应转换失败: %s" % e}


# API 调用串行锁（1.7.103）：设备首屏 5 个接口并发进 Python，插件共享状态（SQLite 连接等）
# 非线程安全 → handler 被插件自身 try/except 吞异常返回"服务内部错误"（PC 串行全部正常实证差异）。
# 全局串行化：插件接口都是毫秒级本地调用，排队无感。
# 1.7.256：改成可重入锁——聊天命令 dispatch 也要走这把锁（原来只有 Web API 加锁，
# 两边会同时改写全局 context.config 和插件实例状态）。RLock 防同一线程内嵌套调用死锁。
_WEB_CALL_LOCK = threading.RLock()


def call_web_api(ids_json, method, path, query_json="", body_json="", configs_json=""):
    """串行入口（1.7.103）：全部 Web API 调用经全局锁排队，消除插件共享状态并发竞态
    （真机首屏 5 并发 → handler 被插件自身 except 吞异常返回"服务内部错误"）。"""
    with _WEB_CALL_LOCK:
        return _call_web_api_locked(ids_json, method, path, query_json, body_json, configs_json)


def _call_web_api_locked(ids_json, method, path, query_json="", body_json="", configs_json=""):
    """WebView 桥入口：按插件注册表匹配并执行 Web API handler。

    Args:
        ids_json: 候选插件 id 列表 JSON（保证插件已 import）。
        method: HTTP 方法（GET/POST/...）。
        path: 请求路径，形如 /{pluginName}/{endpoint}。
        query_json: query 参数对象 JSON（值可为字符串或数组）。
        body_json: 请求体 JSON 文本；上传时为 {"__upload":{filename,content_type,data_b64}}。
        configs_json: {pid: {key: 存储字符串}} 插件配置（注入 context.config，
            handler 修改（含 save_config）后随响应附带 "config"，Kotlin 侧写回存储）。

    Returns:
        {"ok":"true","payload":...,"status":n} 或 {"ok":"false","error":...} 的 JSON 字符串；
        配置有变更时附带 "config"（存储字符串格式）与 "config_pid"。
    """
    try:
        ids = json.loads(ids_json or "[]")
    except Exception:
        ids = []
    import_errors = {}
    try:
        # 配置提前解析：实例化（register_web_api 副作用）前就要拿到 config 构造参数
        try:
            cfg_all = json.loads(configs_json or "{}")
        except Exception:
            cfg_all = {}
        # 确保候选插件已导入（注册表在 import 时填充）
        for pid in ids:
            raw = cfg_all.get(pid) or {}
            _PENDING_CFG[pid] = {k: _coerce(v) for k, v in raw.items()}
            base = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), pid)
            _import_plugin(pid, base)
            _refresh_instance_config(pid, _pending_config(pid))
            reg = _REGISTRY.get(pid) or {}
            if reg.get("import_error"):
                import_errors[pid] = str(reg["import_error"])[:300]
        if not path.startswith("/"):
            path = "/" + path
        handler, path_values, route_pid = _match_web_api(path, method)
        if handler is None:
            return json.dumps({
                "ok": "false",
                "error": "未找到该路由: %s %s" % (method, path),
                "import_errors": import_errors,
            }, ensure_ascii=False)
        # 插件配置注入（与 dispatch 同机制，值按形态反转为真类型；"组.子" 展开为嵌套；
        # handler 可改可 save_config，变更经 _cfg_to_stored 展平写回）
        cfg_pid = route_pid or (ids[0] if ids else "")
        cfg_base = _expand_nested({k: _coerce(v) for k, v in (cfg_all.get(cfg_pid) or {}).items()})
        context.config = _CtxConfig(cfg_pid, cfg_base)
        # query / body
        try:
            qobj = json.loads(query_json or "{}")
        except Exception:
            qobj = {}
        query_pairs = []
        for k, v in (qobj or {}).items():
            if isinstance(v, list):
                for item in v:
                    query_pairs.append((str(k), str(item)))
            else:
                query_pairs.append((str(k), str(v)))
        body_bytes = b""
        form_pairs = []
        file_objs = []
        if body_json:
            try:
                bobj = json.loads(body_json)
            except Exception:
                bobj = None
            if isinstance(bobj, dict) and isinstance(bobj.get("__upload"), dict):
                up = bobj["__upload"]
                try:
                    data = base64.b64decode(up.get("data_b64") or "")
                except Exception:
                    data = b""
                file_objs.append(("file", _UploadFile(
                    up.get("filename"), up.get("content_type"), data, "file")))
                for fk, fv in (bobj.get("fields") or {}).items():
                    form_pairs.append((str(fk), str(fv)))
            elif isinstance(bobj, (dict, list)):
                body_bytes = json.dumps(bobj, ensure_ascii=False).encode("utf-8")
            elif isinstance(body_json, str):
                body_bytes = body_json.encode("utf-8", "replace")
        # 内容类型
        content_type = "application/json" if body_bytes else (
            "multipart/form-data" if file_objs else None)
        plugin_name = path.strip("/").split("/", 1)[0] or None
        req = _CtxReq(
            method=method.upper(), path=path, plugin_name=plugin_name, username="local",
            headers={"content-type": content_type} if content_type else {},
            cookies={}, content_type=content_type,
            path_params=path_values, query_pairs=query_pairs,
            body_bytes=body_bytes, form_pairs=form_pairs, file_objs=file_objs,
        )
        prev = getattr(_CtxReq._local, "req", None)
        _CtxReq._local.req = req
        try:
            r = _await_if_needed(handler(**path_values))
        finally:
            if prev is None:
                try:
                    del _CtxReq._local.req
                except Exception:
                    _CtxReq._local.req = None
            else:
                _CtxReq._local.req = prev
        out = _resp_to_wire(r)
        # 配置变更检测：handler 调了 save_config() 或直接改键 → 附带存储格式配置，Kotlin 侧写回
        try:
            cur = context.config
            if isinstance(cur, _CtxConfig) and (cur._dirty or dict(cur) != cfg_base):
                out["config"] = _cfg_to_stored(cur)
                out["config_pid"] = cur._pid or cfg_pid
                logger.info("[web] 配置变更随响应写回：%s" % (cur._pid or cfg_pid))
        except Exception:
            pass
        if import_errors:
            out["import_errors"] = import_errors
        # 1.7.105 日志桥：插件/垫片 print 输出随响应回传（Kotlin 落 App 日志）
        _logs = _drain_logs_json()
        if _logs:
            out["logs"] = _logs
        return json.dumps(out, ensure_ascii=False, default=str)
    except Exception as e:
        # 1.7.109：完整堆栈进日志桥（真机 handler 异常只给一行 str 无法定位）
        logger.error("[web] call_web_api %s %s 失败: %s" % (method, path, traceback.format_exc(limit=6)))
        _capture_log("call_web_api %s %s 异常:\n%s" % (method, path, traceback.format_exc(limit=6)))
        out = {
            "ok": "false",
            "error": "%s: %s" % (type(e).__name__, e),
            "import_errors": import_errors,
        }
        _logs = _drain_logs_json()
        if _logs:
            out["logs"] = _logs
        return json.dumps(out, ensure_ascii=False)


# ---------------------------------------------------------------- stub 模块
def _install_stub_modules():
    import types

    def mod(name, **attrs):
        m = types.ModuleType(name)
        m.__path__ = []   # 标记为包：允许被 from x.y import ... 作为父包
        for kk, vv in attrs.items():
            setattr(m, kk, vv)
        sys.modules[name] = m
        return m

    _Base = type("AstrBotMessageEvent", (object,), {})
    _EvtBase = type("AstrBotEvent", (object,), {})
    ast_mod = mod("astrbot", logger=logger, context=context)
    api_mod = mod("astrbot.api", logger=logger, AstrBot=type("AstrBot", (), {}))
    core_mod = mod("astrbot.core", register=_register, context=context, command=_command)
    action_mod = mod("astrbot.core.action", plain_result=plain_result, image_result=image_result, command=_command)
    plugin_mod = mod("astrbot.plugin", Plugin=Plugin, PluginConfig=PluginConfig)
    filt_mod = mod("astrbot.filter", command=_filter_command, filter=_FilterMod())
    core_filter = mod("astrbot.core.filter", command=_filter_command, filter=_FilterMod())
    core_base = mod("astrbot.core.base", AstrBotMessageEvent=_Base, AstrBotEvent=_EvtBase)
    api_evt = mod("astrbot.api.event", AstrBotMessageEvent=_Base, AstrBotEvent=_EvtBase,
                  AstrMessageEvent=_Base, AstrBotMessageEventResult=_Base, EventDispatcher=type("EventDispatcher", (object,), {}))
    api_evt.__getattr__ = _module_getattr_factory("astrbot.api.event")
    api_evt_filter = mod(
        "astrbot.api.event.filter",
        command=_filter_command,
        filter=_FilterMod(),
        command_group=_command_group,
        regex=_regex,
        permission_type=_permission_type,
        event_message_type=_event_message_type,
        platform_adapter_type=_platform_adapter_type,
        custom_filter=_custom_filter,
        button_action=_button_action,
        on_plugin_loaded=_on_plugin_loaded,
        on_llm_request=_hook_factory("on_llm_request"),
        on_llm_response=_hook_factory("on_llm_response"),
        on_decorating_result=_hook_factory("on_decorating_result"),
        on_waiting_llm_request=_hook_factory("on_waiting_llm_request"),
        on_agent_begin=_hook_factory("on_agent_begin"),
        on_agent_done=_hook_factory("on_agent_done"),
        on_using_llm_tool=_hook_factory("on_using_llm_tool"),
        on_llm_tool_respond=_hook_factory("on_llm_tool_respond"),
        after_message_sent=_hook_factory("after_message_sent"),
        on_plugin_error=_hook_factory("on_plugin_error"),
        llm_tool=lambda *a, **k: (lambda fn: fn),
    )
    api_evt_filter.__getattr__ = _module_getattr_factory("astrbot.api.event.filter")
    ast_mod.api = api_mod
    ast_mod.core = core_mod
    ast_mod.filter = filt_mod
    ast_mod.plugin = plugin_mod
    api_mod.event = api_evt
    api_evt.filter = api_evt_filter
    core_mod.filter = core_filter
    core_mod.base = core_base
    core_mod.action = action_mod
    # --- Web API 桩（1.7.87：插件视图后端） ---
    web_mod = mod(
        "astrbot.api.web",
        request=request,
        json_response=_json_response,
        error_response=_error_response,
        file_response=_file_response,
        stream_response=_stream_response,
        PluginUploadFile=_UploadFile,
        PluginRequest=_CtxReq,
        PluginMultiDict=_MultiDict,
        PluginRequestProxy=_ReqProxy,
    )
    star_mod = mod(
        "astrbot.api.star",
        Context=type("Context", (object,), {}),
        Star=_StarBase,
        AstrBotPlugin=_StarBase,
        AstrBotMessageEvent=_Base,
        AstrBotEvent=_EvtBase,
        AstrBotMessageEventResult=plain_result,
        Result=_Result,
        register=_register,
        command=_command,
        filter=_filter_command,
        logger=logger,
        context=context,
        EventDispatcher=type("EventDispatcher", (object,), {}),
        AstrBotConfig=_ABConfig,   # 1.7.109：dict 子类（官方语义；旧 PluginConfig 空类不可迭代）
    )
    api_mod.web = web_mod
    api_mod.star = star_mod
    ast_mod.star = star_mod
    core_mod.star = star_mod
    # quart 兼容（旧插件：from quart import jsonify, request）
    quart_mod = mod("quart", request=request, jsonify=_jsonify,
                    Response=type("Response", (object,), {}))
    # --- astrbot.api.all（4.x 插件首选聚合入口）：显式常用符号 + 未知符号万能兜底 ---
    api_all = mod(
        "astrbot.api.all",
        AstrBotConfig=_ABConfig,   # 1.7.109：dict 子类（官方语义；旧裸 dict 无 save_config）
        Image=_Image,
        logger=logger,
        AstrBotMessageEvent=_Base,
        AstrMessageEvent=_Base,
        AstrBotEvent=_EvtBase,
        Context=type("Context", (object,), {}),
        Star=_StarBase,
        AstrBotPlugin=_StarBase,
        AstrBotConfigType=dict,
        Plugin=Plugin,
        PluginConfig=PluginConfig,
        register=_register,
        command=_command,
        filter=_filter_command,
        command_group=_command_group,
        regex=_regex,
        permission_type=_permission_type,
        on_plugin_loaded=_on_plugin_loaded,
        plain_result=plain_result,
        image_result=image_result,
        context=context,
    )
    api_all.__getattr__ = _module_getattr_factory("astrbot.api.all")
    api_mod.all = api_all
    # 消息组件（消息链）：Plain/At/Image/ActionRow/Button —— 按钮插件依赖这些真实类型
    msg_comp = mod(
        "astrbot.api.message_components",
        Plain=_Plain, At=_At, Image=_Image, ActionRow=_ActionRow, Button=_Button,
    )
    api_mod.message_components = msg_comp
    # --- astrbot.core.star.* 层级（GreedyStr / StarTools 等；未知符号兜底） ---
    core_star = mod("astrbot.core.star")
    core_star.__getattr__ = _module_getattr_factory("astrbot.core.star")
    core_star_filter = mod("astrbot.core.star.filter")
    core_star_filter.__getattr__ = _module_getattr_factory("astrbot.core.star.filter")
    core_star_filter_cmd = mod("astrbot.core.star.filter.command", GreedyStr=_GreedyStr)
    core_star_filter_cmd.__getattr__ = _module_getattr_factory("astrbot.core.star.filter.command")
    core_star_tools = mod("astrbot.core.star.star_tools", StarTools=_StarTools)
    core_star_tools.__getattr__ = _module_getattr_factory("astrbot.core.star.star_tools")
    core_star.filter = core_star_filter
    core_star_filter.command = core_star_filter_cmd
    core_star.star_tools = core_star_tools
    core_mod.star = core_star
    ast_mod.star = star_mod
    # 常用入口模块兜底：陌生子模块 import 不再直接失败（返回万能类）
    for _m in ("astrbot", "astrbot.api", "astrbot.core"):
        _mod = sys.modules.get(_m)
        if _mod is not None and not hasattr(_mod, "__getattr__"):
            try:
                _mod.__getattr__ = _module_getattr_factory(_m)
            except Exception:
                pass
    # 兜底范围扩大：所有 astrbot.* / quart / flask / fastapi stub 模块统一挂万能 __getattr__
    # （真实插件 from quart import send_file 等未显式提供的符号不再中断 import）
    for _n, _m in list(sys.modules.items()):
        if not isinstance(_m, types.ModuleType):
            continue
        if not (_n == "astrbot" or _n.startswith("astrbot.") or _n in ("quart", "flask", "fastapi", "pydantic")):
            continue
        if hasattr(_m, "__getattr__"):
            continue
        try:
            _m.__getattr__ = _module_getattr_factory(_n)
        except Exception:
            pass

# ---------------------------------------------------------------- 插件导入（含顶层目录回退）
def _find_entry(base):
    """找入口：根 main.py/plugin.py → 单层子目录 main.py/plugin.py"""
    for name in ("main.py", "plugin.py"):
        p = os.path.join(base, name)
        if os.path.isfile(p):
            return p, base
    try:
        for child in sorted(os.listdir(base)):
            cdir = os.path.join(base, child)
            if not os.path.isdir(cdir) or child.startswith("."):
                continue
            for name in ("main.py", "plugin.py"):
                p = os.path.join(cdir, name)
                if os.path.isfile(p):
                    return p, cdir
    except Exception:
        pass
    return None, base

def _collect_module_commands(module, pid):
    """收集模块级/类级打过 _astr_cmd 标的函数（旧版漏掉模块级 → 命令永不触发）；
    1.7.88 追加：_astr_regex（filter.regex 正则触发）与 _astr_on_loaded（加载钩子）；
    且类方法优先绑定到已实例化对象（self 正确注入）。"""
    reg = _REGISTRY.setdefault(pid, {"main": module, "commands": {}, "aliases": {}, "import_error": ""})
    reg["main"] = module
    reg.setdefault("hooks", [])
    inst = reg.get("inst")

    def _bind(obj, name=None):
        if inst is not None and name and inspect.isfunction(obj):
            b = getattr(inst, name, None)
            if b is not None and getattr(b, "__func__", None) is obj:
                return b
        return obj

    def _collect(obj, name=None):
        if not callable(obj):
            return
        handler = _bind(obj, name)
        cmdname = getattr(obj, "_astr_cmd", None)
        if cmdname:
            reg["commands"].setdefault(cmdname, handler)
        rx = getattr(obj, "_astr_regex", None)
        if rx:
            reg["commands"].setdefault("regex::" + str(rx), handler)
        if getattr(obj, "_astr_on_loaded", None):
            if handler not in reg["hooks"]:
                reg["hooks"].append(handler)
        # filter.event_message_type 消息监听：无命令命中时统一分发（上游 4.x 常见形态）
        if getattr(obj, "_astr_event", None):
            reg.setdefault("events", [])
            if handler not in reg["events"]:
                reg["events"].append(handler)
        # filter.button_action：按钮点击回调（key=按钮ID，"*"=匹配全部）
        ba_key = getattr(obj, "_astr_button_action", None)
        if ba_key is not None:
            reg.setdefault("button_actions", {}).setdefault(str(ba_key), [])
            if handler not in reg["button_actions"][str(ba_key)]:
                reg["button_actions"][str(ba_key)].append(handler)
        hook_kind = getattr(obj, "_astr_hook", None)
        if hook_kind:
            reg.setdefault("hook_kinds", {}).setdefault(str(hook_kind), [])
            if handler not in reg["hook_kinds"][str(hook_kind)]:
                reg["hook_kinds"][str(hook_kind)].append(handler)
        for alias in getattr(obj, "_astr_alias", []) or []:
            reg["aliases"][alias] = cmdname or ""

    for attr in list(vars(module).values()):
        _collect(attr)
        if isinstance(attr, type):
            for hname in dir(attr):
                if hname.startswith("__"):
                    continue
                try:
                    _collect(getattr(attr, hname), hname)
                except Exception:
                    continue


def _ensure_instance(pid, module):
    """兜底实例化：4.x 插件常不带 @register 装饰（框架自动扫 Star 子类），
    此前只有装饰路径会实例化 → inst=None → initialize/register_web_api 永不执行。"""
    reg = _REGISTRY.get(pid) or {}
    if reg.get("inst") is not None:
        return reg.get("inst")
    # 传 _CtxConfig（带 save_config）：插件 __init__ 常 self.config = config 原样覆盖（1.7.88）
    cfg = _CtxConfig(pid, _pending_config(pid))
    for v in list(vars(module).values()):
        # 主类判定：定义于插件自身模块（排除垫片基类 Star 自身）且继承 Star
        if not isinstance(v, type) or v is _StarBase:
            continue
        if not str(getattr(v, "__module__", "")).startswith(pid):
            continue
        if _StarBase not in getattr(v, "__mro__", ()):
            continue
        inst = None
        for args in ((context, cfg), (context,), (cfg,), (), (None, cfg), (None, None), (None,)):
            try:
                inst = v(*args)
                break
            except TypeError:
                continue
            except Exception:
                logger.error("插件 %s(%s) 实例化异常: %s" % (pid, v.__name__, traceback.format_exc(limit=2)))
                break
        if inst is not None:
            reg["inst"] = inst
            logger.info("[lifecycle] 插件 %s 兜底实例化：%s" % (pid, v.__name__))
            return inst
        logger.warning("插件 %s(%s) 兜底实例化失败（构造签名不兼容）" % (pid, v.__name__))
    return None

# 插件导入串行锁（1.7.95）：Web 首屏并发多请求（overview/checkin-groups/…同时到达）会
# 竞态 exec_module 同一插件——半初始化模块被并发使用导致注册表/实例状态错乱（交互时灵时不灵）。
_IMPORT_LOCK = threading.Lock()


def _import_plugin(pid, base):
    if pid in _LOADED:
        return
    with _IMPORT_LOCK:
        if pid in _LOADED:  # double-check：等锁期间可能已被其它线程完成
            return
        _import_plugin_inner(pid, base)


def _import_plugin_inner(pid, base):
    if pid in _LOADED:
        return
    main_py, root = _find_entry(base)
    if main_py is None:
        reg = _REGISTRY.setdefault(pid, {"main": None, "commands": {}, "aliases": {}, "import_error": ""})
        reg["import_error"] = "未找到 main.py/plugin.py（已查根目录与单层子目录）"
        return
    global _current_plugin_id
    _current_plugin_id = pid
    modname = pid
    if modname in sys.modules:
        module = sys.modules[modname]
    else:
        # 以"包"形式加载（submodule_search_locations）：main.py 兼作包 __init__，
        # 插件内的相对导入 from .checkin import ... 解析为 {pid}.checkin（1.7.88 修复多文件包 import）
        spec = importlib.util.spec_from_file_location(
            modname, main_py, submodule_search_locations=[root]
        )
        if spec is None or spec.loader is None:
            return
        module = importlib.util.module_from_spec(spec)
        sys.modules[modname] = module
        old_path = list(sys.path)
        if root not in sys.path:
            sys.path.insert(0, root)
        try:
            spec.loader.exec_module(module)
        except Exception:
            reg = _REGISTRY.setdefault(pid, {"main": module, "commands": {}, "aliases": {}, "import_error": ""})
            reg["import_error"] = traceback.format_exc(limit=3)
            return
        finally:
            sys.path[:] = old_path
    _REGISTRY.setdefault(pid, {"main": module, "commands": {}, "aliases": {}, "import_error": ""})
    _REGISTRY[pid]["main"] = module
    # 1.7.88：先兜底实例化（4.x 无 @register 装饰的插件），再收集（类方法绑定实例），再跑生命周期
    _ensure_instance(pid, module)
    _collect_module_commands(module, pid)
    # 生命周期（1.7.88 关键）：AstrBot 框架加载插件时会 await initialize()——
    # 插件的 plugin_web_api.register()（register_web_api）等关键副作用都在其中；
    # 不调用则 Web API 注册表恒空 → 页面按钮必 404。
    _run_lifecycle(pid)
    _LOADED.add(pid)


def _run_lifecycle(pid):
    """插件加载生命周期：initialize() + filter.on_plugin_loaded 钩子（失败仅记日志，不阻断分发）"""
    reg = _REGISTRY.get(pid) or {}
    inst = reg.get("inst")
    init = getattr(inst, "initialize", None) if inst is not None else None
    if callable(init):
        try:
            _await_if_needed(init())
            logger.info("[lifecycle] 插件 %s initialize() 完成" % pid)
        except Exception:
            logger.error("[lifecycle] %s initialize() 失败: %s" % (pid, traceback.format_exc(limit=2)))
    for h in list(reg.get("hooks") or []):
        try:
            _await_if_needed(h(None))
        except Exception:
            logger.warning("[lifecycle] %s on_plugin_loaded 钩子失败: %s" % (pid, traceback.format_exc(limit=1)))

# ---------------------------------------------------------------- 调用与分发
# 持久事件循环（修“待久后超时”）：旧版每次 asyncio.run()/new_event_loop()+close()，
# 反复建毁 loop 会让插件里绑定 loop 的 Lock/Queue/ClientSession 失效（cannot reuse / different loop）。
_loop_ref = None


def _get_loop():
    """复用同一个事件循环；没有则创建一次并缓存。"""
    global _loop_ref
    try:
        return asyncio.get_running_loop()
    except RuntimeError:
        if _loop_ref is None or _loop_ref.is_closed():
            _loop_ref = asyncio.new_event_loop()
            asyncio.set_event_loop(_loop_ref)
        return _loop_ref


def ensure_loop_alive():
    """Kotlin 侧健康检查入口：验证/重建事件循环，返回 "true"/"false"。"""
    try:
        loop = _get_loop()
        if loop.is_closed():
            return "false"
        async def _ping():
            await asyncio.sleep(0.01)
            return True
        if loop.is_running():
            fut = asyncio.run_coroutine_threadsafe(_ping(), loop)
            return "true" if fut.result(timeout=2.0) else "false"
        return "true" if loop.run_until_complete(_ping()) else "false"
    except Exception:
        return "false"


_PLUGIN_CALL_TIMEOUT = 120  # 秒：单个插件协程的最长执行时间（超时取消，避免永久卡死调用线程）


def _run_coro(coro):
    """在持久事件循环上执行协程（修“待久后超时”核心）。
    旧版用 asyncio.run()：每次新建并关闭 loop —— 插件里绑定 loop 的 asyncio.Lock /
    Queue / aiohttp.ClientSession 在第二次调用起就会报 "bound to a different event
    loop" / "cannot reuse"，表现为网页接口超时。现在统一复用 _get_loop()：
      · loop 未运行 → 本线程 run_until_complete；
      · loop 已被其它线程运行 → run_coroutine_threadsafe 投递并等待结果。
    1.7.256：两条路径都加超时（原来 fut.result() 无限等待，一个挂死的插件协程会永久卡住
    Kotlin 调用线程 → UI/消息线程无响应）。
    """
    loop = _get_loop()
    try:
        running = asyncio.get_running_loop()
    except RuntimeError:
        running = None
    if running is not None and running is loop:
        # 已在 loop 线程内：无法同步等待自身，交给调用方（实际不会走到：Kotlin 走 Java 线程）
        raise RuntimeError("shim loop 重入")
    if loop.is_running():
        fut = asyncio.run_coroutine_threadsafe(coro, loop)
        try:
            return fut.result(timeout=_PLUGIN_CALL_TIMEOUT)
        except concurrent.futures.TimeoutError:
            fut.cancel()
            msg = "插件协程超时（>%ss），已取消，避免卡死调用线程" % _PLUGIN_CALL_TIMEOUT
            logger.error(msg)
            raise TimeoutError(msg)
    return loop.run_until_complete(asyncio.wait_for(coro, _PLUGIN_CALL_TIMEOUT))


def _await_if_needed(r):
    # 异步生成器（async def ... yield，AstrBot 处理器常见形态）：消费全部产出
    if inspect.isasyncgen(r):
        async def _drain(g):
            out = []
            async for it in g:
                out.append(it)
            return out
        return _run_coro(_drain(r))
    # 同步生成器（def ... yield）
    if inspect.isgenerator(r):
        return list(r)
    if inspect.iscoroutine(r):
        return _run_coro(r)
    return r

def _attempts_for(handler, ev, arg):
    try:
        n = len([p for p in inspect.signature(handler).parameters if p not in ("self", "cls")])
    except Exception:
        try:
            n = max(handler.__code__.co_argcount - 1, 0)
        except Exception:
            n = 1
    if n <= 0:
        return [lambda: handler()]
    if n == 1:
        # 末位补 handler()：命令组头（filter.command_group 装饰的函数通常 0 参）被单独输入时
        # 不应报“处理器签名不兼容”
        return [lambda: handler(ev), lambda: handler(arg), lambda: handler()]
    if n == 2:
        return [lambda: handler(ev, arg), lambda: handler(None, ev), lambda: handler()]
    return [lambda: handler(None, ev, arg), lambda: handler(ev, arg), lambda: handler()]

def _split_result(r):
    """分发结果 → (回复文本, 按钮行列表)。支持 _Result / list / 字符串"""
    items = r if isinstance(r, list) else [r]
    parts, rows = [], []
    for it in items:
        if isinstance(it, _Result):
            if it.kind == "chain" and isinstance(it.payload, dict):
                p = it.payload
                if p.get("text"):
                    parts.append(str(p["text"]))
                for u in (p.get("images") or []):
                    parts.append(str(u))
                rows.extend(p.get("rows") or [])
            elif it.kind == "image":
                parts.append("🔗 %s" % it.payload)
            else:
                parts.append(str(it.payload))
        elif it is None:
            continue
        elif isinstance(it, (dict, list)):
            parts.append(json.dumps(it, ensure_ascii=False))
        else:
            parts.append(str(it))
    return "\n".join(p for p in parts if p), rows[:5]


def _render_result(r):
    """分发结果 → 回复文本：支持 _Result / list[_Result]（async 生成器多产出）/ 字符串"""
    return _split_result(r)[0]


def dispatch(ids_json, text, session, configs_json=""):
    """聊天命令派发入口：与 Web API 共用同一把可重入锁（1.7.256），
    避免「聊天命令」和「网页接口」同时改写插件实例/context.config 的竞态。"""
    with _WEB_CALL_LOCK:
        return _dispatch_locked(ids_json, text, session, configs_json)


def _dispatch_locked(ids_json, text, session, configs_json=""):
    ids = json.loads(ids_json or "[]")
    configs = {}
    if configs_json:
        try:
            configs = json.loads(configs_json)
        except Exception:
            configs = {}
    import_errors = {}
    ev = _Event(text, session=session or "")
    for pid in ids:
        # 预置配置（实例化/刷新用）：coerce 真类型
        if configs_json:
            raw = (configs.get(pid) or {})
            _PENDING_CFG[pid] = {k: _coerce(v) for k, v in raw.items()}
        base = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), pid)
        _import_plugin(pid, base)
        _refresh_instance_config(pid, _pending_config(pid))
        reg = _REGISTRY.get(pid) or {}
        if reg.get("import_error"):
            import_errors[pid] = str(reg["import_error"])[:300]
        commands = reg.get("commands") or {}
        aliases = reg.get("aliases") or {}
        body = text.strip().lstrip("/")
        plain = body.split(" ")[0].lower()
        # 选出「最长匹配」而不是先到先得：命令组头 math 与子命令 math add 同时存在时，
        # 旧逻辑按字典顺序先命中 math → 以错误参数调用组头 → “处理器签名不兼容”。
        best = None  # (matched_len, handler, arg)
        for name, handler in commands.items():
            # regex 触发（filter.regex 打标，key 形如 regex::<pattern>）
            if str(name).startswith("regex::"):
                try:
                    if re.search(str(name)[7:], text) is None:
                        continue
                except re.error:
                    continue
                hit, arg, matched_len = True, text, len(text)
            else:
                n = str(name).lower()
                # aliases 是 {别名: 命令名}：反向收集该命令的所有别名，按实际命中的词截取参数
                alias_names = [str(k).lower() for k, v in aliases.items() if str(v).lower() == n]
                hit = False
                arg = ""
                matched_len = len(name)
                for tok in [n] + alias_names:
                    if not tok:
                        continue
                    if body.lower().startswith(tok):
                        rest = body[len(tok):]
                        if rest == "" or rest[0] in " :/":
                            hit = True
                            matched_len = len(tok)
                            break
                if hit:
                    arg = body[matched_len:].lstrip(" :/").strip()
            if hit and (best is None or matched_len > best[0]):
                best = (matched_len, handler, arg)
        if best is not None:
            handler, arg = best[1], best[2]
            # 注入该插件的真实配置（插件页保存的 key=value；值按形态反转 + "组.子" 展开嵌套）
            raw_cfg = configs.get(pid, {}) or {}
            context.config = _CtxConfig(
                pid, _expand_nested({k: _coerce(v) for k, v in raw_cfg.items()})
            )
            _refresh_instance_config(pid, _pending_config(pid))
            last_err = None
            for call in _attempts_for(handler, ev, arg):
                try:
                    r = _await_if_needed(call())
                    reply, rows = _split_result(r)
                    out = {"ok": "true", "reply": reply}
                    if rows:
                        out["buttons"] = rows
                    return json.dumps(out)
                except TypeError:
                    continue
                except Exception as e:
                    last_err = e
            return json.dumps({
                "ok": "false",
                "error": f"{last_err}" if last_err else "处理器签名不兼容",
                "import_errors": import_errors,
            })
        # 无命令命中 → 跑 @filter.event_message_type 消息监听（上游 4.x 常见形态）
        for handler in list(reg.get("events") or []):
            raw_cfg = configs.get(pid, {}) or {}
            context.config = _CtxConfig(
                pid, _expand_nested({k: _coerce(v) for k, v in raw_cfg.items()})
            )
            _refresh_instance_config(pid, _pending_config(pid))
            last_err = None
            for call in _attempts_for(handler, ev, ""):
                try:
                    reply, rows = _split_result(_await_if_needed(call()))
                    if reply or rows:
                        out = {"ok": "true", "reply": reply}
                        if rows:
                            out["buttons"] = rows
                        return json.dumps(out)
                    break
                except TypeError:
                    continue
                except Exception as e:
                    last_err = e
            if last_err is not None:
                logger.debug("[event] %s 消息监听处理失败: %s" % (pid, last_err))
    # 无命中：若有导入失败，透出（Kotlin 侧记日志，不回给用户刷屏）
    out = {"ok": "false", "error": "", "import_errors": import_errors}
    _logs = _drain_logs_json()
    if _logs:
        out["logs"] = _logs
    return json.dumps(out)


class _ButtonInteraction:
    def __init__(self, payload):
        self.action_id = str(payload.get("action_id") or "")
        raw = payload.get("data")
        if isinstance(raw, str):
            try:
                raw = json.loads(raw)
            except Exception:
                pass
        self.data = raw if raw is not None else {}
        self.message_id = str(payload.get("message_id") or "")
        self.user_id = str(payload.get("user_id") or "")


class _ButtonEvent(_Event):
    """按钮点击事件：插件用 event.get_button_interaction() 读 action_id / data"""
    def __init__(self, payload):
        super().__init__("", session=str(payload.get("session") or ""))
        self._interaction = _ButtonInteraction(payload)
        self.sender = type("ButtonSender", (), {
            "id": self._interaction.user_id, "username": "", "nickname": ""
        })()

    def get_button_interaction(self, *a, **k):
        return self._interaction

    def get_sender_id(self, *a, **k):
        return self._interaction.user_id


def handle_button_interaction(ids_json, payload_json):
    """Kotlin 侧 INTERACTION_CREATE 入口：找到 @filter.button_action 处理器并执行。
    返回 {ok, reply, buttons, import_errors}，与 dispatch 同格式。"""
    try:
        ids = json.loads(ids_json or "[]")
        payload = json.loads(payload_json or "{}")
    except Exception:
        return json.dumps({"ok": "false", "error": "bad payload"})
    action_id = str(payload.get("action_id") or "")
    ev = _ButtonEvent(payload)
    import_errors = {}
    for pid in ids:
        base = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), pid)
        try:
            _import_plugin(pid, base)
        except Exception:
            continue
        reg = _REGISTRY.get(pid) or {}
        if reg.get("import_error"):
            import_errors[pid] = str(reg["import_error"])[:200]
        ba = reg.get("button_actions") or {}
        handlers = list(ba.get(action_id, [])) + list(ba.get("*", []))
        for h in handlers:
            last_err = None
            for call in _attempts_for(h, ev, ""):
                try:
                    reply, rows = _split_result(_await_if_needed(call()))
                    out = {"ok": "true", "reply": reply}
                    if rows:
                        out["buttons"] = rows
                    return json.dumps(out)
                except TypeError:
                    continue
                except Exception as e:
                    last_err = e
            if last_err is not None:
                logger.warning("[button] %s 回调失败: %s" % (pid, last_err))
    out = {"ok": "false", "error": "", "import_errors": import_errors}
    return json.dumps(out)

_install_stub_modules()
_install_meta_finder()
