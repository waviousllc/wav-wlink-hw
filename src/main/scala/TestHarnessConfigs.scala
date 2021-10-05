package wav.wlink

import wav.common._

import chisel3._
import chisel3.util._
import chisel3.experimental._
import chisel3.stage.ChiselStage

import freechips.rocketchip.config.{Parameters, Field, Config}
import freechips.rocketchip.diplomacy._


class WithWlinkSimpleAXITestConfig(
  numTxLanes  : Int = 1,
  numRxLanes  : Int = 1,
  size        : BigInt = 0x100000,
  beatBytes   : Int = 4,
  idBits      : Int = 4,
) extends Config((site, here, up) => {
  case MonitorsEnabled => false
  
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



class AXIWlinkTestRegressConfig(
  numTxLanes  : Int = 1,
  numRxLanes  : Int = 1,
  size        : BigInt = 0x100000,
  beatBytes   : Int = 4,
  idBits      : Int = 4,
) extends Config(
  new WithWlinkSimpleAXITestConfig(
    numTxLanes = numTxLanes, 
    numRxLanes = numRxLanes,
    beatBytes  = beatBytes) ++
  new BaseWlinkTestConfig
)



class BaseWlinkTestConfig extends Config(
  new WithWavComponentPrefix("testing")
)


class AXI32bit1LaneWlinkTestConfig extends Config(
  new WithWlinkSimpleAXITestConfig(
    numTxLanes = 1, 
    numRxLanes = 1,
    beatBytes  = 4) ++
  new BaseWlinkTestConfig
)

class AXI64bit1LaneWlinkTestConfig extends Config(
  new WithWlinkSimpleAXITestConfig(
    numTxLanes = 1, 
    numRxLanes = 1,
    beatBytes  = 8) ++
  new BaseWlinkTestConfig
)

class AXI128bit1LaneWlinkTestConfig extends Config(
  new WithWlinkSimpleAXITestConfig(
    numTxLanes = 1, 
    numRxLanes = 1,
    beatBytes  = 16) ++
  new BaseWlinkTestConfig
)

class AXI256bit1LaneWlinkTestConfig extends Config(
  new WithWlinkSimpleAXITestConfig(
    numTxLanes = 1, 
    numRxLanes = 1,
    beatBytes  = 32) ++
  new BaseWlinkTestConfig
)

class AXI32bit2LaneWlinkTestConfig extends Config(
  new WithWlinkSimpleAXITestConfig(
    numTxLanes = 2, 
    numRxLanes = 2,
    beatBytes  = 4) ++
  new BaseWlinkTestConfig
)

class AXI64bit3LaneWlinkTestConfig extends Config(
  new WithWlinkSimpleAXITestConfig(
    numTxLanes = 3, 
    numRxLanes = 3,
    beatBytes  = 8) ++
  new BaseWlinkTestConfig
)

class AXI128bit4LaneWlinkTestConfig extends Config(
  new WithWlinkSimpleAXITestConfig(
    numTxLanes = 4, 
    numRxLanes = 4,
    beatBytes  = 16) ++
  new BaseWlinkTestConfig
)

class AXI256bit5LaneWlinkTestConfig extends Config(
  new WithWlinkSimpleAXITestConfig(
    numTxLanes = 5, 
    numRxLanes = 5,
    beatBytes  = 32) ++
  new BaseWlinkTestConfig
)

class AXI128bit6LaneWlinkTestConfig extends Config(
  new WithWlinkSimpleAXITestConfig(
    numTxLanes = 6, 
    numRxLanes = 6,
    beatBytes  = 16) ++
  new BaseWlinkTestConfig
)
