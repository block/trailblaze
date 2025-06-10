package xyz.block.trailblaze.toolcalls

import maestro.orchestra.Command

interface MapsToMaestroCommands {
  fun toMaestroCommands(): List<Command>
}
