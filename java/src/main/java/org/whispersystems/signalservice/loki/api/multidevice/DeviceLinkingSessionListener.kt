package org.whispersystems.signalservice.loki.api.multidevice

interface DeviceLinkingSessionListener {

  fun requestUserAuthorization(authorisation: DeviceLink) {}
  fun onDeviceLinkRequestAuthorized(authorisation: DeviceLink) {}
}
