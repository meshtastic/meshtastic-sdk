- An admin reply from another node completes a request, or refreshes that node's session passkey,
  only when it is PKI-encrypted or arrives on the legacy `admin` channel. `from` and `request_id`
  travel in the clear, so a reply under a shared channel key could otherwise be forged.
