#Minimal makefile for Wlink
SHELL=/bin/bash

CONFIG		?= wav.wlink.Wlink1LaneAXI32bitConfig
OUTPUTDIR	?= $(CONFIG)
TEST_CONFIG	?= wav.wlink.AXI32bit1LaneWlinkTestConfig
TEST_OUTPUTDIR	?= $(TEST_CONFIG)



help:
	@echo "Wlink Generator Makefile"
	@echo ""
	@echo "CONFIG    = <package.Config> # Configuration used to generate make wlink"
	@echo "OUTPUTDIR = <outputdir/>     # Directory for output files of make wlink"
	@echo ""
	@echo "# To generate a single Wlink instance for design integration"
	@echo "make wlink CONFIG=<config> OUTPUTDIR=<outputdir>"
	@echo ""
	@echo "TEST_CONFIG    = <package.Config> # Configuration used to generate make testharness"
	@echo "TEST_OUTPUTDIR = <outputdir/>     # Directory for output files of make testharness"
	@echo ""
	@echo "# To generate a WlinkSimpleTestHarness to run simple simulations"
	@echo "make testharness TEST_CONFIG=<config> TEST_OUTPUTDIR=<outputidr>"
	@echo ""

.PHONY: help 

wlink: 	
	@echo "Making Wlink with CONFIG: $(CONFIG) and saving to $(OUTPUTDIR)"
	sbt 'runMain wav.wlink.WlinkGen -o $(OUTPUTDIR) -c $(CONFIG)'

# Ensure that the testharness config is one that is supported for the testharness!!!
testharness: 	
	@echo "Making TestHarness with TEST_CONFIG: $(TEST_CONFIG) and saving to $(TEST_OUTPUTDIR)"
	sbt 'runMain wav.wlink.WlinkTHGen -o $(TEST_OUTPUTDIR) -c $(TEST_CONFIG)'
