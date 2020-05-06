package org.whispersystems.signalservice.loki.protocol.meta

import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage
import org.whispersystems.signalservice.api.messages.SignalServiceGroup

object SessionMetaProtocol {

    @JvmStatic
    public fun canSyncDataMessage(message: SignalServiceDataMessage): Boolean {
        if (message.isFriendRequest || message.preKeyBundle.isPresent || message.deviceLink.isPresent) { return false }
        // TODO: I think the code below is just trying to check that this isn't a background message
        return message.body.isPresent || message.attachments.isPresent
            || message.sticker.isPresent || message.quote.isPresent
            || message.contacts.isPresent || message.previews.isPresent
            || canSyncGroupDataMessage(message)
    }

    @JvmStatic
    public fun canSyncGroupDataMessage(message: SignalServiceDataMessage): Boolean {
        return message.group.isPresent() && message.group.get().getGroupType() == SignalServiceGroup.GroupType.SIGNAL
    }
}
