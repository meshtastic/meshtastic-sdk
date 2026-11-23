- Local admin on a node with `security.is_managed` is sent once the link has reported a lockdown
  status. Firmware built with PhoneAPI access control reports one on every link and accepts local
  admin from a link it has authorized; the SDK returned `Unauthorized` without sending.
