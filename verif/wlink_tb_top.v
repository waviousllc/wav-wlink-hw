`timescale 1ns/1ps

module wlink_tb_top;

`include "sim_msg.vh"

reg clock = 0;
always #1ns clock <= ~clock;
reg reset = 0;

reg hsclk = 0;
always #31.25ps hsclk <= ~hsclk;

wire finished;

initial begin
  if($test$plusargs("NO_WAVES")) begin
    `sim_info($display("No waveform saving this sim"))
  end else begin
    $dumpvars(0);
  end
  #10ps;
  reset <= 1;
  
  #10ns;
  reset <= 0;
  
  wait(finished);
  @(posedge clock);
  if(error) begin
    `sim_error($display("Errors seen!"))
  end else begin
    `sim_info($display("No errors seen!"))
  end
  $finish;
end


initial begin
  #10ms;
  `sim_fatal($display("Simulation TIMEOUT!!!"))
end


WlinkSimpleTestHarness u_WlinkSimpleTestHarness (
  .clock             ( clock              ),  //input -  1              
  .reset             ( reset              ),  //input -  1              
  .hsclk             ( hsclk              ),  //input -  1              
  .finished          ( finished           ),  //output - 1              
  .error             ( error              )); //output - 1 


endmodule
