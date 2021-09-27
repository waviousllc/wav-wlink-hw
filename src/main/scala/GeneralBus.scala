package wav.wlink

import wav.common._

//import Chisel._
import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import chisel3.experimental.IO
import chisel3.stage.ChiselStage

//import chisel3.experimental._
import freechips.rocketchip.amba._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.amba.ahb._
import freechips.rocketchip.amba.apb._
import freechips.rocketchip.subsystem.BaseSubsystem
import freechips.rocketchip.subsystem.CrossingWrapper
import freechips.rocketchip.config.{Parameters, Field, Config}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.regmapper._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util._
import freechips.rocketchip.unittest._
import freechips.rocketchip.devices.tilelink._

import chisel3.internal.sourceinfo.SourceInfo
import chisel3.util.random.FibonacciLFSR

import scala.math.max


case class WlinkGeneralBusParams(
  width     : Int = 8,
  name      : String = "generalbus",
  fifoSize  : Int = 4
)



object WlinkGeneralBusCreatePorts{
  def apply(nodes: scala.collection.mutable.Buffer[(GeneralBusToWlink, WlinkGeneralBusParams, Boolean)])(implicit p: Parameters): Unit = {
    nodes.foreach{ case (node, params, connected) =>
      
      // Since we aren't using Diplomacy (Yet) for the GeneralBus, we need a way to reference
      // the module, then to create AND connect the busses up to the top level. Since
      // this can be done at various levels, we will use BoringUtils to bore a connection
      // between the location this is called and the pointer to the conversion node. Magic!
      if(!connected){
        val boringPorts = InModuleBody {    
          val gbus_in   = IO(Input (UInt(params.width.W)))
          val gbus_out  = IO(Output(UInt(params.width.W)))
          gbus_in.suggestName (params.name+"_in")
          gbus_out.suggestName(params.name+"_out")

          val gbus_in_wire  = Wire(UInt(params.width.W))
          val gbus_out_wire = Wire(UInt(params.width.W))

          //Why this tied off? for some reason I get an uninitialized exception
          //if I don't tie this off to something. Still seems to work post boring
          gbus_out_wire := 0.U

          gbus_in_wire := gbus_in
          gbus_out     := gbus_out_wire

          BoringUtils.bore(gbus_in_wire, Seq(node.module.bus_in))
          BoringUtils.bore(node.module.bus_out, Seq(gbus_out_wire))
          
        }
      }
      
    }
  }
}


trait CanHaveGeneralBusPort{ this: Wlink =>
  
  private val gbParamsOpt = params.gbParams
  private var index        = 0x0
  
  val gb2wlNodes = scala.collection.mutable.Buffer[(GeneralBusToWlink, WlinkGeneralBusParams, Boolean)]()
  
  gbParamsOpt.map(paramsmap =>
    paramsmap.foreach{gbParams =>
      val gbname = "wlink_" + gbParams.name
      val gb2wl  = LazyModule(new GeneralBusToWlink(shortPacketStart = currentShortPacketIndex,
                                                    longPacketStart  = currentLongPacketIndex,
                                                    baseAddr         = wlinkBaseAddr + params.gbFCOffset + index, 
                                                    name             = gbname,
                                                    width            = gbParams.width,
                                                    fifoSize         = gbParams.fifoSize,
                                                    noRegTest        = params.noRegTest))
                                               
      
      val temp = (gb2wl, gbParams, false)
      gb2wlNodes += temp
                                                     
      txrouter.node     := gb2wl.gb.txnode

      gb2wl.gb.rxnode   := rxrouter.node

      gb2wl.node        := xbar.node

      
      // Connect the clocks/resets/enables internally
      val connectingClocksEnables = InModuleBody { 
        gb2wl.module.io.app.clk              := app_clk_scan
        gb2wl.module.io.app.reset            := app_clk_reset_scan
        gb2wl.module.io.app.enable           := swi_enable

        gb2wl.module.io.tx.clk               := tx_link_clk
        gb2wl.module.io.tx.reset             := tx_link_clk_reset

        gb2wl.module.io.rx.clk               := rx_link_clk
        gb2wl.module.io.rx.reset             := rx_link_clk_reset
        
        crc_errors(crcErrIndex)              := gb2wl.module.io.rx.crc_err       
        crcErrIndex                          +=1  
      }
      
      
      
      
      index = index + 0x100
      
      
      currentLongPacketIndex  +=1
      currentShortPacketIndex +=4
    }
  )
  
  if(p(WlinkOnlyGen)){
    WlinkGeneralBusCreatePorts(gb2wlNodes)
  }
  
}


class GeneralBusToWlink(
  shortPacketStart  : Int = 0x8,
  longPacketStart   : Int = 0x40,
  baseAddr          : BigInt = 0x0,
  width             : Int = 8,
  name              : String = "",
  fifoSize          : Int = 4,
  noRegTest         : Boolean = false
)(implicit p: Parameters) extends LazyModule{
  
  val node = APBIdentityNode()
  
  val chPrefix = if(name == "") "" else s"${name}_"
  
  val gb = LazyModule(new WlinkGenericFCSM(baseAddr        = baseAddr, 
                                           a2lDataWidth    = width, 
                                           a2lDepth        = fifoSize, 
                                           l2aDataWidth    = width, 
                                           l2aDepth        = fifoSize, 
                                           channelName     = chPrefix + "gb", 
                                           dataIdDefault   = longPacketStart,
                                           crIdDefault     = shortPacketStart,
                                           crackIdDefault  = shortPacketStart + 1,
                                           ackIdDefault    = shortPacketStart + 2,
                                           nackIdDefault   = shortPacketStart + 3,
                                           noRegTest       = noRegTest))
  gb.suggestName(name+"gb")
  gb.node := node
  
  lazy val module = new LazyModuleImp(this) with RequireAsyncReset{
    
    val io = IO(new Bundle{
      val app = new Bundle{
        val clk         = Input (Bool())
        val reset       = Input (Bool())
        val enable      = Input (Bool())
      }
      
      val tx = new Bundle{
        val clk     = Input (Bool())
        val reset   = Input (Bool())
      }
      
      val rx = new Bundle{
        val clk     = Input (Bool())
        val reset   = Input (Bool())
        val crc_err = Output(Bool())
      }
    })
    
    io.rx.crc_err   := gb.module.io.rx.crc_err
    
    val bus_in = Wire(UInt(width.W))
    bus_in      := 0.U  //see above for why this is tied off like this
    val bus_out = Wire(UInt(width.W))
    
    gb.module.io.tx.clk           := io.tx.clk
    gb.module.io.tx.reset         := io.tx.reset
    gb.module.io.rx.clk           := io.rx.clk
    gb.module.io.rx.reset         := io.rx.reset
    
    gb.module.io.app.clk          := io.app.clk
    gb.module.io.app.reset        := io.app.reset
    gb.module.io.app.enable       := io.app.enable
    
    withClockAndReset(io.app.clk.asClock, io.app.reset.asAsyncReset){
      //val bus_ff2       = WavDemetReset(io.bus_in)
      val bus_ff2       = WavDemetReset(bus_in)
      val bus_ff3       = RegNext(bus_ff2, 0.U)
      val bus_diff      = bus_ff2 ^ bus_ff3
      val diff_seen     = bus_diff.orR
      
      val a2l_valid_in  = Wire(Bool())
      val a2l_valid     = RegNext(a2l_valid_in, false.B)
      a2l_valid_in      := Mux(gb.module.io.app.a2l_ready && ~diff_seen, false.B, Mux(diff_seen, true.B, a2l_valid))
      val a2l_data      = bus_ff2
      
      
      gb.module.io.app.a2l_valid  := a2l_valid
      gb.module.io.app.a2l_data   := a2l_data
      
      
      val l2a_data_in   = Wire(UInt(width.W))
      val l2a_data      = RegNext(l2a_data_in, 0.U)
      l2a_data_in       := Mux(gb.module.io.app.l2a_valid, gb.module.io.app.l2a_data, l2a_data)
      
      gb.module.io.app.l2a_accept := WavDemetReset(io.app.enable)
      
      //io.bus_out        := l2a_data
      bus_out        := l2a_data
    }
    
  }
}
