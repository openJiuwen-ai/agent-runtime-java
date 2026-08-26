# Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.

"""Independent FastMCP server used by the Agent Service MCP demo."""

import json
import logging
import os
import time

from mcp.server.fastmcp import FastMCP


DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 18080
MAX_DELAY_MS = 60_000

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s - %(message)s",
)
LOGGER = logging.getLogger("openjiuwen.demo.fastmcp")


def _read_port() -> int:
    """Read and validate the FastMCP listen port."""
    value = os.getenv("DEMO_FASTMCP_PORT", str(DEFAULT_PORT))
    try:
        port = int(value)
    except ValueError as exc:
        raise ValueError("DEMO_FASTMCP_PORT must be an integer") from exc
    if port < 1 or port > 65_535:
        raise ValueError("DEMO_FASTMCP_PORT must be between 1 and 65535")
    return port


HOST = os.getenv("DEMO_FASTMCP_HOST", DEFAULT_HOST)
PORT = _read_port()

mcp = FastMCP(
    "openjiuwen-demo-fastmcp",
    instructions="JSON-only Streamable HTTP MCP server for the OpenJiuwen demo.",
    host=HOST,
    port=PORT,
    stateless_http=True,
    json_response=True,
)


@mcp.tool()
def demo_echo(text: str) -> str:
    """Return the supplied text with a stable demo prefix."""
    LOGGER.info("MCP_TOOL_CALL tool=demo_echo text_length=%d", len(text))
    return f"demo_echo:{text}"


@mcp.tool()
def demo_delay(delay_ms: int) -> str:
    """Delay the response to exercise timeout and circuit-breaker policies."""
    if delay_ms < 0 or delay_ms > MAX_DELAY_MS:
        raise ValueError(f"delay_ms must be between 0 and {MAX_DELAY_MS}")
    LOGGER.info("MCP_TOOL_CALL tool=demo_delay arguments=%s", json.dumps({"delay_ms": delay_ms}))
    time.sleep(delay_ms / 1000)
    return f"demo_delay:{delay_ms}"


@mcp.tool()
def demo_fail() -> str:
    """Return a FastMCP tool error for error-result boundary demonstrations."""
    LOGGER.info("MCP_TOOL_CALL tool=demo_fail arguments={}")
    raise RuntimeError("demo_fail requested failure")


if __name__ == "__main__":
    LOGGER.info("Starting FastMCP server endpoint=http://%s:%s/mcp", HOST, PORT)
    mcp.run(transport="streamable-http")
