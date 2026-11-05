from __future__ import annotations

from typing import TYPE_CHECKING

from java import dynamic_proxy  # ty: ignore[unresolved-import]

from com.xiao.feng.features.api.core import WeDatabaseListenerApi  # ty: ignore[unresolved-import]
from xfeng.runtime import PluginContext

if TYPE_CHECKING:
    from xfeng.runtime import HookParameter


def setup(ctx: PluginContext) -> None:
    ctx.log.info("Demo loading")

    def on_call(parameter: HookParameter) -> None:
        ctx.log.info(f"called with {parameter.args}")

    class Listener(dynamic_proxy(WeDatabaseListenerApi.IInsertListener)):
        def onInsert(self, table: str, values: object) -> None:
            ctx.log.info(f"insert: {table}")

    listener = ctx.track_reference(Listener())
    WeDatabaseListenerApi.INSTANCE.addListener(listener)
    ctx.defer(lambda: WeDatabaseListenerApi.INSTANCE.removeListener(listener))
    ctx.log.info("Demo loaded")
