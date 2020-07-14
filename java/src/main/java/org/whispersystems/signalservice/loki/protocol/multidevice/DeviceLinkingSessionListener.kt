package org.whispersystems.signalservice.loki.protocol.multidevice

interface DeviceLinkingSessionListener {

  fun requestUserAuthorization(deviceLink: DeviceLink) { }
  fun onDeviceLinkRequestAuthorized(deviceLink: DeviceLink) { }
}
