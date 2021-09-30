#!/bin/bash


VVP_FILE=wlink_tb
LOG="-lvvp.log"

outputdir="../output"
waveform=""

while [[ "$#" -gt 0 ]]; do
    case $1 in
        -o|--outputdir) outputdir="$2"; shift ;;
	-l|--lxt2)      waveform="-lxt2-speed"; shift ;;
        *) echo "Unknown parameter passed: $1"; exit 1 ;;
    esac
    shift
done

rm -rf $VVP_FILE

echo "Compiling"
iverilog -g2012 -DSIMULATION \
  $outputdir/*.v  $outputdir/*.sv \
  wlink_tb_top.v \
  -s wlink_tb_top \
  -o $VVP_FILE


echo "Running"
vvp -n $LOG $VVP_FILE $waveform
