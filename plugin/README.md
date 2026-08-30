# Herdroid Bridge plugin

This manifest-only plugin packages the `herdroid-bridge` SSH-stdio companion.
It registers no startup daemon, events, actions, listener, or credential store.

Install the matching target directory with stock `herdr plugin link`; Herdroid
selects and verifies the manifest and raw binary before upload.
