Setting Up Sphinx Documentation Dir
-----------------------------------

create virtualenv

  virtualenv -p /usr/bin/python3 venv

Active the venv

  source venv/bin/activate.csh    (use .csh for centos machines)

Install sphinx
  
  pip install sphinx
  pip install rst2pdf
  pip install sphinx-rtd-theme
  
  
Test the installation

  sphinx-build -help

Create Initial Sphinx dir

  sphinx-quickstart

Create as needed



