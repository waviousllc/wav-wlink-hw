package wav.wlinktests

import chisel3._
import chiseltest._
import chiseltest.experimental.TestOptionBuilder._
import chiseltest.internal._
import org.scalatest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.ParallelTestExecution

import wav.wlink._

import freechips.rocketchip.config.{Parameters, Field, Config}
import freechips.rocketchip.diplomacy._

class WlinkTestHarnessTests extends AnyFlatSpec with ChiselScalatestTester with Matchers /*with ParallelTestExecution*/{
  behavior of "Wlink Test Harness"
  
  //val annos = Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)
  val annos = Seq(VerilatorBackendAnnotation)
  
  //val axiBeatBytesList = List(4,8,16,32,64,128)  
  val axiBeatBytesList = List(4,8,32)  
  //val axiBeatBytesList = List(16)  
  val axiSizeList= List(x"1000", x"9_1452_1000")
  //val axiSizeList= List(x"100000")
  
  for(lanes <- List(1, 4, 8, 16); 
      axiBeatBytes <- axiBeatBytesList;
      axiSize      <- axiSizeList){
  
    it should s"test ${lanes}txLanes ${lanes}rxLanes with ${axiBeatBytes} axiBeatBytes with ${axiSize.toString(16)} as axiSize" in {

      implicit val p: Parameters = new AXIWlinkTestRegressConfig(numTxLanes = lanes,
                                                                 numRxLanes = lanes,
								 beatBytes  = axiBeatBytes,
								 size       = axiSize )

      test(LazyModule(new WlinkSimpleTestHarness()(p)).module).withAnnotations(annos) { dut =>
	
	
	var count = 0
	dut.hsclk.poke(true.B)

	for(i <- 0 until 10){
          dut.clock.step()
          dut.hsclk.poke(false.B)
          dut.clock.step()
          dut.hsclk.poke(true.B)
	}


	while((dut.finished.peek().litValue() == 0) && (count < 999999)){
          dut.clock.step()
          dut.hsclk.poke(false.B)
          dut.clock.step()
          dut.hsclk.poke(true.B)
          count = count + 1
	}

	if(count > 999999) println("counter expired!")

	dut.finished.peek().litValue() should be (1)
	dut.error.peek().litValue() should be (0)
      }
    }
  }
  
}
