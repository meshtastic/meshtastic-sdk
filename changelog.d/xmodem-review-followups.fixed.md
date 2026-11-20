- XModem file transfer no longer sends `CAN` where firmware would delete a file it should keep
  (after a refused upload request, or after the final `EOT`), re-acknowledges a block the device
  repeats after a lost ACK, ignores a late cleanup reply from an earlier download attempt, and
  documents the full timeout bounds.
