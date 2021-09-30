package wav.wlink

import wav.common._

import chisel3._
import chisel3.util._
import chisel3.experimental._
import chisel3.stage.ChiselStage

import freechips.rocketchip.config.{Parameters, Field, Config}
import freechips.rocketchip.diplomacy._


/*
.rst_start
Wlink Configs
================
Wlink utilizes ``Configs`` for describing how each instance of Wlink looks. Each ``Config``
can be different to target a different instance of Wlink. Each ``Config`` is generally made
up of several smaller config fragments. These are individual fragments that usually target
a certain portion of the design.

Let's take a look at a simple config fragment
.rst_end
*/

//.code_block_start scala
class WithWlinkDisableTLMonitors extends  Config((site, here, up) => {
  case MonitorsEnabled => false
})
//.code_block_end
/*
.rst_start
This config fragment disables TileLink Monitors by setting the ``MonitorsEnabled`` key to false.

One nice feature about ``Configs`` is that you can declare several fragments in one and then extend
from that fragment for generating a larger ``Config``. Let's look at the ``BaseWlinkConfig`` which
we usually extend from.
.rst_end
*/

//.code_block_start scala
class BaseWlinkConfig extends Config(
  new WithWlinkDisableTLMonitors ++
  new WithWlinkOnlyGen ++
  new WithWavComponentPrefix("wlink")
)
//.code_block_end


/*
.rst_start
Here we have defined the ``BaseWlinkConfig``. It starts with the ``WithWavComponentPrefix`` config fragment.
We then add ``WithWlinkOnlyGen`` to indicate we are generating Wlink as the top level component, and
finally we add our ``WithWlinkDisableTLMonitors``.

This ``Config`` doesn't seem all that interesting, so let's see what else we can create.

WithWlinkGPIOAXIConfig Example
------------------------------
Let's say I want to use Wlink with the basic GPIO-based PHY and AXI. Depending on my implementation
I want to be able to change the number of lanes and possibly the AXI parameters. Let's create a config
fragment that gives some configurability.
.rst_end
*/

//.code_block_start scala
class WithWlinkGPIOAXIConfig(
  numTxLanes  : Int = 1,
  numRxLanes  : Int = 1,
  size        : BigInt = 0x100000,
  beatBytes   : Int = 4,
  idBits      : Int = 4,
) extends Config((site, here, up) => {

  case WlinkParamsKey => WlinkParams(
    phyParams = WlinkPHYGPIOExampleParams(
      numTxLanes = numTxLanes,
      numRxLanes = numRxLanes,
    ),
    axiParams = Some(Seq(WlinkAxiParams(
      base = 0x0,
      size = size,
      beatBytes = beatBytes,
      idBits = idBits)))
  )
})
//.code_block_end

/*
.rst_start
This config fragment allows a user to pass the lanes, size, beatBytes, and id size anytime they mixin 
this config fragment. Let's look below at how we can create two separate instances of the Wlink.


.rst_end
*/

//.code_block_start scala
class Wlink1LaneAXI32bitConfig extends Config(
  new WithWlinkGPIOAXIConfig(
    numTxLanes = 1,
    numRxLanes = 1,
    beatBytes  = 4) ++
  new BaseWlinkConfig
)

class Wlink1LaneAXI64bitConfig extends Config(
  new WithWlinkGPIOAXIConfig(
    numTxLanes = 1,
    numRxLanes = 1,
    beatBytes  = 8) ++
  new BaseWlinkConfig
)


class Wlink8LaneAXI256bitConfig extends Config(
  new WithWlinkGPIOAXIConfig(
    numTxLanes = 8,
    numRxLanes = 8,
    beatBytes  = 32) ++
  new BaseWlinkConfig
)

//.code_block_end


/*
.rst_start
For more details on ``Configs`` it is strongly recommend to look over the Chipyard project

* https://chipyard.readthedocs.io/en/latest/Chipyard-Basics/Configs-Parameters-Mixins.html
* https://chipyard.readthedocs.io/en/latest/Advanced-Concepts/CDEs.html

.rst_end
*/




class WithWlinkGPIOTwoAXIOneAPBTgtConfig(
  numTxLanes      : Int = 1,
  numRxLanes      : Int = 1,
  axi1Size        : BigInt = 0x100000,
  axi1BeatBytes   : Int = 4,
  axi1IdBits      : Int = 4,
  axi2Size        : BigInt = 0x4000000,
  axi2BeatBytes   : Int = 16,
  axi2IdBits      : Int = 4,
  apbSize         : BigInt = 0x2000
) extends Config((site, here, up) => {

  case WlinkParamsKey => WlinkParams(
    phyParams = WlinkPHYGPIOExampleParams(
      numTxLanes = numTxLanes,
      numRxLanes = numRxLanes,
    ),
    axiParams = Some(Seq(
      WlinkAxiParams(
        base                = 0x0,
        size                = axi1Size,
        beatBytes           = axi1BeatBytes,
        idBits              = axi1IdBits,
        name                = "axi1",
        startingLongDataId  = 0x80,
        startingShortDataId = 0x4),
      WlinkAxiParams(
        base                = 0x0,
        size                = axi2Size,
        beatBytes           = axi2BeatBytes,
        idBits              = axi2IdBits,
        name                = "axi2",
        startingLongDataId  = 0x90,
        startingShortDataId = 0x20)
      )),
    apbTgtParams = Some(Seq(
      WlinkApbTgtParams(
        base                = 0x0,
        size                = apbSize,
        name                = "apb",
        startingLongDataId  = 0xa0,
        startingShortDataId = 0x38
      )
    ))
  )
})

class Wlink8LaneTwoAXIPortsOneAPBPortConfig extends Config(
  new WithWlinkGPIOTwoAXIOneAPBTgtConfig() ++
  new BaseWlinkConfig
)
