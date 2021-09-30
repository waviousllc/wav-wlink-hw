#!/usr/bin/python36

import os
import sys
import argparse   as ap
import getpass
import datetime
import math
import re

username = getpass.getuser()

infile     = ""
outfile    = None
rst_str    = """"""

##########################
def footer():
  """Small footer placed at the end of the file to signify who ran and when"""
  yr  = datetime.date.today().strftime("%Y")
  day = datetime.date.today().strftime("%d")
  mon = datetime.date.today().strftime("%B")
  t   = datetime.datetime.now().strftime("%H:%M:%S")
  
  str = """
  
.. generated using get_rst.py by {0} at {1}

""".format(username, mon+"/"+day+"/"+yr+"  "+t)
  print(str)
  
##########################
def remove_leading_spaces(str):
  """Removes the leading spaces starting from the rst block. Looks for '.rst_start'
     and figures up how many spaces are from start of line to that keyword."""
  
  
  # Get leading spces for this block
  spaces = 0
#  for l in str.splitlines():
#    if ".rst_start" in l:
#      spaces = len(l) - len(l.lstrip())
#      break
  
  for l in str.splitlines():
    p = re.compile(".rst_start")
    m = p.search(l)
    if m:
      #print("start {}".format(m.span()[0]))
      spaces = m.span()[0]
      break
  
  new_str = """"""
  for l in str.splitlines():
    if (".rst_start" not in l) and (".rst_end" not in l):
      new_str += l[spaces:] 
      new_str += """\n"""

  new_str += """\n"""   
  
  return new_str
  
  
##########################
def parse_file(infile):
  global rst_str
  
  try:
    os.path.isfile(infile)
  except FileNotFoundError:
    print("Error: File '{}' doesn't seem to exist!".format(infile))
    set_err()
  else:
    with open(infile) as f:
      captured_rst = """"""
      in_rst  = False
      in_cb   = False
      
      for line in f:
        if not in_rst:
          #Check for the start of a rst block
          if ".rst_start" in line:
            captured_rst = line
            in_rst       = True
            
        else:
          if ".rst_end" in line:
            in_rst = False
            captured_rst += line
            rst_str += remove_leading_spaces(captured_rst)
            captured_rst = """"""
          else:
            captured_rst += line
        
        #Need to clean this up
        if not in_cb and not in_rst:    
          #check for the start of a code-block that is not in the rst block
          #we will just grab everything here as is until .end_code_block
          if ".code_block_start" in line:
            lang = "verilog"
            m = re.search(r'code_block_start\s+(.*?)\n', line)
            if m:
              lang = m.group(1)
            captured_rst = ".. code-block :: {}\n\n".format(lang)
            in_cb        = True
            
        elif in_cb:
          if ".code_block_end" in line:
            in_cb        = False
            captured_rst += """\n\n"""
            rst_str      += captured_rst
            captured_rst = """"""
          else:
            captured_rst += """  """+line #Give indent for code
    

##########################
def get_args():
  parser = ap.ArgumentParser(description="A tool for extracting reStructuredText from a file")
  parser.add_argument("-i", "-input_file",      help="Input file", type=str)
  parser.add_argument("-o", "-output_file",     help="Output file. Prints to console if not specified", type=str)
  
  args = parser.parse_args()
  
  if args.i:
    global infile
    infile = args.i
  else:
    print("Error: An input file was not specified!")
    parser.print_help(sys.stderr)
    sys.exit(1)
  
  if args.o:
    global outfile
    outfile = args.o
    
  
  
############################
# Run
############################
get_args()
parse_file(infile)


if outfile:
  f = open(outfile, 'w')
  sys.stdout = f

print(rst_str)
footer()
