Common Issues/Error Messages
=================================



Data ID Overlap
----------------
::

  [error] (run-main-0) java.lang.IllegalArgumentException: requirement failed: Data ID # is already used for <name>!!

You have likely created two application ports of the same protocol and didn't set the starting short/long packet IDs 
for the second port.
