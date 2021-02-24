import { Meteor } from 'meteor/meteor';
import { check } from 'meteor/check';
import RedisPubSub from '/imports/startup/server/redis';
import Logger from '/imports/startup/server/logger';
import Users from '/imports/api/users';

export default function userLeaving(meetingId, userId, connectionId) {
  const REDIS_CONFIG = Meteor.settings.private.redis;
  const CHANNEL = REDIS_CONFIG.channels.toAkkaApps;
  const EVENT_NAME = 'UserLeaveReqMsg';

  check(userId, String);

  const selector = {
    meetingId,
    userId,
  };

  const User = Users.findOne(selector);

  if (!User) {
    Logger.info(`Skipping userLeaving. Could not find ${userId} in ${meetingId}`);
    return;
  }

  // If the current user connection is not the same that triggered the leave we skip
  if (User.connectionId !== connectionId) {
    Logger.info(`Skipping userLeaving. User connectionId=${User.connectionId} is different from requester connectionId=${connectionId}`);
    return false;
  }

  const payload = {
    userId,
    sessionId: meetingId,
  };

  Logger.info(`User '${userId}' is leaving meeting '${meetingId}'`);
  return RedisPubSub.publishUserMessage(CHANNEL, EVENT_NAME, meetingId, userId, payload);
}
