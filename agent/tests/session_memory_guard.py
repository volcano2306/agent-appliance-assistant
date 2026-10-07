"""Install before importing application modules; no secret-file exceptions."""
import os
from pathlib import Path
import sys
from types import ModuleType

_model_host = None
_model_port = None
_model_ips = set()


def configure_live_network(host, port):
    """Call only before installing the audit hook, after explicit --live consent.

    Resolve only the selected provider. No redirects/proxies/business hosts.
    """
    import socket
    global _model_host, _model_port, _model_ips
    _model_host, _model_port = host.lower(), port
    _model_ips = {item[4][0] for item in socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)}


def _stdlib_wakeup_pipe(event, args):
    # Windows asyncio creates its wakeup socketpair through a loopback TCP pipe.
    # Only that exact stdlib call stack is permitted; arbitrary localhost clients
    # (MongoDB, Java, model proxies) remain forbidden.
    if event not in {"socket.connect", "socket.bind"} or len(args) < 2:
        return False
    address = args[1]
    if not isinstance(address, tuple) or address[0] not in {"127.0.0.1", "::1"}:
        return False
    expected = (Path(sys.base_prefix) / "Lib/socket.py").resolve()
    frame = sys._getframe(1)
    while frame is not None:
        if frame.f_code.co_name in {"_fallback_socketpair", "socketpair"} and Path(frame.f_code.co_filename).resolve() == expected:
            return True
        frame = frame.f_back
    return False


def audit_guard(event, args):
    if event == "open" and args and isinstance(args[0], (str, bytes, os.PathLike)):
        name = Path(os.fsdecode(args[0])).name.lower()
        if name == ".env" or name.startswith(".env."):
            raise PermissionError("Session memory tests forbid dotenv access")
    if event in {"socket.connect", "socket.bind", "socket.getaddrinfo",
                 "subprocess.Popen", "os.system"}:
        if _stdlib_wakeup_pipe(event, args):
            return
        if event == "socket.getaddrinfo" and _model_host and str(args[0]).lower() == _model_host and args[1] == _model_port:
            return
        if event == "socket.connect" and _model_host and len(args) > 1 and isinstance(args[1], tuple):
            if args[1][0] in _model_ips and args[1][1] == _model_port:
                return
        raise PermissionError("Session memory tests forbid network and subprocesses")


def guard_embedding_runtime():
    """Business embeddings are forbidden in these model/contract regressions.

    Avoid importing an optional ONNX runtime just to collect fake-tool tests;
    constructing an embedding model remains a hard failure, not a fake vector.
    """
    def forbidden_embedding(*args, **kwargs):
        raise PermissionError("Offline tests forbid embedding model initialization")

    module = ModuleType("fastembed")
    module.TextEmbedding = forbidden_embedding
    sys.modules["fastembed"] = module




def install():
    sys.addaudithook(audit_guard)
    sys.dont_write_bytecode = True
    kept = {key: value for key, value in os.environ.items()
            if key.upper() in {"SYSTEMROOT", "WINDIR", "TEMP", "TMP", "PATH"}}
    os.environ.clear()
    os.environ.update(kept)
    os.environ["PYTEST_DISABLE_PLUGIN_AUTOLOAD"] = "1"
    guard_embedding_runtime()


def guard_settings():
    from app import config
    original = config.Settings.__init__

    def explicit(self, **kwargs):
        if kwargs.get("_env_file", object()) is not None:
            raise AssertionError("Session memory tests require _env_file=None")
        return original(self, **kwargs)

    def forbidden(*args, **kwargs):
        raise AssertionError("Default settings are forbidden in session memory tests")

    config.Settings.__init__ = explicit
    config.get_settings = forbidden
