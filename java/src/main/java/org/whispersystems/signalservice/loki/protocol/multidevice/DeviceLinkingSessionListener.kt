package org.whispersystems.signalservice.loki.protocol.multidevice

interface DeviceLinkingSessionListener {

  fun requestUserAuthorization(authorisation: DeviceLink) {}
  fun onDeviceLinkRequestAuthorized(authorisation: DeviceLink) {}
}
