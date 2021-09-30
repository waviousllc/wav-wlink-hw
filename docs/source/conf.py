# Configuration file for the Sphinx documentation builder.
#
# This file only contains a selection of the most common options. For a full
# list see the documentation:
# https://www.sphinx-doc.org/en/master/usage/configuration.html

# -- Path setup --------------------------------------------------------------

# If extensions (or modules to document with autodoc) are in another directory,
# add these directories to sys.path here. If the directory is relative to the
# documentation root, use os.path.abspath to make it absolute, like shown here.
#
# import os
# import sys
# sys.path.insert(0, os.path.abspath('.'))


# -- Project information -----------------------------------------------------

project = 'Wlink'
copyright = '2021, Wavious'
author = 'Wavious'

# The full version, including alpha/beta/rc tags
release = '0.1'


## -- General configuration ---------------------------------------------------
#
## Add any Sphinx extension module names here, as strings. They can be
## extensions coming with Sphinx (named 'sphinx.ext.*') or your custom
## ones.
#extensions = [
#]
#
## Add any paths that contain templates here, relative to this directory.
#templates_path = ['_templates']
#
## List of patterns, relative to source directory, that match files and
## directories to ignore when looking for source files.
## This pattern also affects html_static_path and html_extra_path.
#exclude_patterns = []
#
#
## -- Options for HTML output -------------------------------------------------
#
## The theme to use for HTML and HTML Help pages.  See the documentation for
## a list of builtin themes.
##
#html_theme = 'alabaster'
#
## Add any paths that contain custom static files (such as style sheets) here,
## relative to this directory. They are copied after the builtin static files,
## so a file named "default.css" will overwrite the builtin "default.css".
#html_static_path = ['_static']
#


extensions = ['sphinx.ext.autodoc', 'sphinx.ext.autosectionlabel', 'rst2pdf.pdfbuilder', 'sphinx.ext.todo']  
pygments_style = 'sphinx'
html_theme = 'sphinx_rtd_theme'
html_logo = '/home/sbridges/wavious_logo.png'
html_static_path = ['_static']

pdf_stylesheets = ['/prj/wavious/r0_tsmc28hpc/iceng/work/sbridges/scripts/gen_regs_py_doc/rst2pdf.stylesheet.rts']
pdf_style_path = ['.']
pdf_font_path = ['/usr/share/fonts/liberation', '/usr/share/fonts/google-crosextra-carlito']
# -- Options for HTML output -------------------------------------------------

pdf_break_level = 2
pdf_breakside = 'any'
pdf_cover_template = 'cover.tmpl'
pdf_documents = [('index', u'Wlink', u'Wlink', u'Wavious'),]
