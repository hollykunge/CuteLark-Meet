package org.bigbluebutton.core.running

import java.io.{ PrintWriter, StringWriter }

import akka.actor._
import akka.actor.SupervisorStrategy.Resume
import org.bigbluebutton.SystemConfiguration
import org.bigbluebutton.core.apps.groupchats.GroupChatHdlrs
import org.bigbluebutton.core.apps.presentationpod._
import org.bigbluebutton.core.apps.users._
import org.bigbluebutton.core.apps.whiteboard.ClientToServerLatencyTracerMsgHdlr
import org.bigbluebutton.core.domain._
import org.bigbluebutton.core.util.TimeUtil
import org.bigbluebutton.common2.domain.{ DefaultProps, LockSettingsProps }
import org.bigbluebutton.core.api._
import org.bigbluebutton.core.apps._
import org.bigbluebutton.core.apps.caption.CaptionApp2x
import org.bigbluebutton.core.apps.chat.ChatApp2x
import org.bigbluebutton.core.apps.screenshare.ScreenshareApp2x
import org.bigbluebutton.core.apps.presentation.PresentationApp2x
import org.bigbluebutton.core.apps.users.UsersApp2x
import org.bigbluebutton.core.apps.sharednotes.SharedNotesApp2x
import org.bigbluebutton.core.apps.whiteboard.WhiteboardApp2x
import org.bigbluebutton.core.bus._
import org.bigbluebutton.core.models._
import org.bigbluebutton.core2.{ MeetingStatus2x, Permissions }
import org.bigbluebutton.core2.message.handlers._
import org.bigbluebutton.core2.message.handlers.meeting._
import org.bigbluebutton.common2.msgs._
import org.bigbluebutton.core.apps.breakout._
import org.bigbluebutton.core.apps.polls._
import org.bigbluebutton.core.apps.voice._
import akka.actor._
import akka.actor.SupervisorStrategy.Resume

import scala.concurrent.duration._
import org.bigbluebutton.core.apps.layout.LayoutApp2x
import org.bigbluebutton.core.apps.meeting.{ SyncGetMeetingInfoRespMsgHdlr, ValidateConnAuthTokenSysMsgHdlr }
import org.bigbluebutton.core.apps.users.ChangeLockSettingsInMeetingCmdMsgHdlr
import org.bigbluebutton.core2.message.senders.{ MsgBuilder, Sender }

import scala.concurrent.ExecutionContext.Implicits.global

object MeetingActor {
  def props(
      props:       DefaultProps,
      eventBus:    InternalEventBus,
      outGW:       OutMsgRouter,
      liveMeeting: LiveMeeting
  ): Props =
    Props(classOf[MeetingActor], props, eventBus, outGW, liveMeeting)
}

class MeetingActor(
    val props:       DefaultProps,
    val eventBus:    InternalEventBus,
    val outGW:       OutMsgRouter,
    val liveMeeting: LiveMeeting
)
  extends BaseMeetingActor
  with SystemConfiguration
  with GuestsApp
  with LayoutApp2x
  with VoiceApp2x
  with BreakoutApp2x
  with UsersApp2x

  with UserBroadcastCamStartMsgHdlr
  with UserJoinMeetingReqMsgHdlr
  with UserJoinMeetingAfterReconnectReqMsgHdlr
  with UserBroadcastCamStopMsgHdlr
  with UserConnectedToGlobalAudioMsgHdlr
  with UserDisconnectedFromGlobalAudioMsgHdlr
  with MuteAllExceptPresentersCmdMsgHdlr
  with MuteMeetingCmdMsgHdlr
  with IsMeetingMutedReqMsgHdlr

  with EjectUserFromVoiceCmdMsgHdlr
  with EndMeetingSysCmdMsgHdlr
  with DestroyMeetingSysCmdMsgHdlr
  with SendTimeRemainingUpdateHdlr
  with SendBreakoutTimeRemainingMsgHdlr
  with ChangeLockSettingsInMeetingCmdMsgHdlr
  with SyncGetMeetingInfoRespMsgHdlr
  with ClientToServerLatencyTracerMsgHdlr
  with ValidateConnAuthTokenSysMsgHdlr
  with UserActivitySignCmdMsgHdlr {

  object CheckVoiceRecordingInternalMsg
  object SyncVoiceUserStatusInternalMsg

  override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries = 10, withinTimeRange = 1 minute) {
    case e: Exception => {
      val sw: StringWriter = new StringWriter()
      sw.write("An exception has been thrown on MeetingActor, exception message [" + e.getMessage() + "] (full stacktrace below)\n")
      e.printStackTrace(new PrintWriter(sw))
      log.error(sw.toString())
      Resume
    }
  }

  /**
   * Put the internal message injector into another actor so this
   * actor is easy to test.
   */
  var actorMonitor = context.actorOf(
    MeetingActorAudit.props(props, eventBus, outGW),
    "actorMonitor-" + props.meetingProp.intId
  )

  val msgBus = MessageBus(eventBus, outGW)

  val presentationApp2x = new PresentationApp2x
  val screenshareApp2x = new ScreenshareApp2x
  val captionApp2x = new CaptionApp2x
  val sharedNotesApp2x = new SharedNotesApp2x
  val chatApp2x = new ChatApp2x
  val usersApp = new UsersApp(liveMeeting, outGW, eventBus)
  val groupChatApp = new GroupChatHdlrs
  val presentationPodsApp = new PresentationPodHdlrs
  val pollApp = new PollApp2x
  val wbApp = new WhiteboardApp2x

  object ExpiryTrackerHelper extends MeetingExpiryTrackerHelper

  val inactivityTracker = new MeetingInactivityTracker(
    TimeUtil.minutesToMillis(props.durationProps.maxInactivityTimeoutMinutes),
    TimeUtil.minutesToMillis(props.durationProps.warnMinutesBeforeMax),
    lastActivityTimestampInMs = TimeUtil.timeNowInMs(),
    warningSent = false,
    warningSentOnTimestampInMs = 0L
  )

  val expiryTracker = new MeetingExpiryTracker(
    startedOnInMs = TimeUtil.timeNowInMs(),
    userHasJoined = false,
    isBreakout = props.meetingProp.isBreakout,
    lastUserLeftOnInMs = None,
    durationInMs = TimeUtil.minutesToMillis(props.durationProps.duration),
    meetingExpireIfNoUserJoinedInMs = TimeUtil.minutesToMillis(props.durationProps.meetingExpireIfNoUserJoinedInMinutes),
    meetingExpireWhenLastUserLeftInMs = TimeUtil.minutesToMillis(props.durationProps.meetingExpireWhenLastUserLeftInMinutes),
    userInactivityInspectTimerInMs = TimeUtil.minutesToMillis(props.durationProps.userInactivityInspectTimerInMinutes),
    userInactivityThresholdInMs = TimeUtil.minutesToMillis(props.durationProps.userInactivityThresholdInMinutes),
    userActivitySignResponseDelayInMs = TimeUtil.minutesToMillis(props.durationProps.userActivitySignResponseDelayInMinutes)
  )

  val recordingTracker = new MeetingRecordingTracker(startedOnInMs = 0L, previousDurationInMs = 0L, currentDurationInMs = 0L)

  var state = new MeetingState2x(
    new GroupChats(Map.empty),
    new PresentationPodManager(Map.empty),
    None,
    inactivityTracker,
    expiryTracker,
    recordingTracker
  )

  var lastRttTestSentOn = System.currentTimeMillis()

  // Create a default public group chat
  state = groupChatApp.handleCreateDefaultPublicGroupChat(state, liveMeeting, msgBus)

  //state = GroupChatApp.genTestChatMsgHistory(GroupChatApp.MAIN_PUBLIC_CHAT, state, BbbSystemConst.SYSTEM_USER, liveMeeting)
  // Create a default public group chat **DEPRECATED, NOT GOING TO WORK ANYMORE**
  //state = GroupChatApp.createDefaultPublicGroupChat("TEST_GROUP_CHAT", state)
  //state = GroupChatApp.genTestChatMsgHistory("TEST_GROUP_CHAT", state, BbbSystemConst.SYSTEM_USER, liveMeeting)

  log.debug("NUM GROUP CHATS = " + state.groupChats.findAllPublicChats().length)

  // Create a default Presentation Pod
  state = presentationPodsApp.handleCreateDefaultPresentationPod(state, liveMeeting, msgBus)

  log.debug("NUM Presentation Pods = " + state.presentationPodManager.getNumberOfPods())

  // Initialize if the meeting is muted on start
  if (props.voiceProp.muteOnStart) {
    MeetingStatus2x.muteMeeting(liveMeeting.status)
  } else {
    MeetingStatus2x.unmuteMeeting(liveMeeting.status)
  }

  // Set webcamsOnlyForModerator property in case we didn't after meeting creation
  MeetingStatus2x.setWebcamsOnlyForModerator(liveMeeting.status, liveMeeting.props.usersProp.webcamsOnlyForModerator)

  initLockSettings(liveMeeting, liveMeeting.props.lockSettingsProps)

  /** *****************************************************************/
  // Helper to create fake users for testing (ralam jan 5, 2018)
  //object FakeTestData extends FakeTestData
  //FakeTestData.createFakeUsers(liveMeeting)
  /** *****************************************************************/

  context.system.scheduler.schedule(
    5 seconds,
    syncVoiceUsersStatusInterval seconds,
    self,
    SyncVoiceUserStatusInternalMsg
  )

  context.system.scheduler.schedule(
    5 seconds,
    checkVoiceRecordingInterval seconds,
    self,
    CheckVoiceRecordingInternalMsg
  )

  def receive = {
    case SyncVoiceUserStatusInternalMsg =>
      checkVoiceConfUsersStatus()
    case CheckVoiceRecordingInternalMsg =>
      checkVoiceConfIsRunningAndRecording()

    //=============================

    // 2x messages
    case msg: BbbCommonEnvCoreMsg             => handleBbbCommonEnvCoreMsg(msg)

    // Handling RegisterUserReqMsg as it is forwarded from BBBActor and
    // its type is not BbbCommonEnvCoreMsg
    case m: RegisterUserReqMsg                => usersApp.handleRegisterUserReqMsg(m)

    case m: EjectDuplicateUserReqMsg          => usersApp.handleEjectDuplicateUserReqMsg(m)
    case m: GetAllMeetingsReqMsg              => handleGetAllMeetingsReqMsg(m)
    case m: ValidateConnAuthTokenSysMsg       => handleValidateConnAuthTokenSysMsg(m)

    // Meeting
    case m: DestroyMeetingSysCmdMsg           => handleDestroyMeetingSysCmdMsg(m)

    //======================================

    //=======================================
    // internal messages
    case msg: MonitorNumberOfUsersInternalMsg => handleMonitorNumberOfUsers(msg)

    case msg: ExtendMeetingDuration           => handleExtendMeetingDuration(msg)
    case msg: SendTimeRemainingAuditInternalMsg =>
      state = handleSendTimeRemainingUpdate(msg, state)
      state = handleSendBreakoutTimeRemainingMsg(msg, state)
    case msg: BreakoutRoomCreatedInternalMsg     => state = handleBreakoutRoomCreatedInternalMsg(msg, state)
    case msg: SendBreakoutUsersAuditInternalMsg  => handleSendBreakoutUsersUpdateInternalMsg(msg)
    case msg: BreakoutRoomUsersUpdateInternalMsg => state = handleBreakoutRoomUsersUpdateInternalMsg(msg, state)
    case msg: EndBreakoutRoomInternalMsg         => handleEndBreakoutRoomInternalMsg(msg)
    case msg: BreakoutRoomEndedInternalMsg       => state = handleBreakoutRoomEndedInternalMsg(msg, state)

    // Screenshare
    case msg: DeskShareGetDeskShareInfoRequest   => handleDeskShareGetDeskShareInfoRequest(msg)

    case msg: SendRecordingTimerInternalMsg =>
      state = usersApp.handleSendRecordingTimerInternalMsg(msg, state)

    case _ => // do nothing
  }

  private def initLockSettings(liveMeeting: LiveMeeting, lockSettingsProp: LockSettingsProps): Unit = {
    val settings = Permissions(
      disableCam = lockSettingsProp.disableCam,
      disableMic = lockSettingsProp.disableMic,
      disablePrivChat = lockSettingsProp.disablePrivateChat,
      disablePubChat = lockSettingsProp.disablePublicChat,
      disableNote = lockSettingsProp.disableNote,
      hideUserList = lockSettingsProp.hideUserList,
      lockedLayout = lockSettingsProp.lockedLayout,
      lockOnJoin = lockSettingsProp.lockOnJoin,
      lockOnJoinConfigurable = lockSettingsProp.lockOnJoinConfigurable
    )

    MeetingStatus2x.initializePermissions(liveMeeting.status)

    MeetingStatus2x.setPermissions(liveMeeting.status, settings)

  }

  private def updateInactivityTracker(state: MeetingState2x): MeetingState2x = {
    val tracker = state.inactivityTracker.updateLastActivityTimestamp(TimeUtil.timeNowInMs())
    state.update(tracker)
  }

  private def updateVoiceUserLastActivity(userId: String) {
    for {
      vu <- VoiceUsers.findWithVoiceUserId(liveMeeting.voiceUsers, userId)
    } yield {
      updateUserLastActivity(vu.intId)
    }
  }

  private def updateUserLastActivity(userId: String) {
    for {
      user <- Users2x.findWithIntId(liveMeeting.users2x, userId)
    } yield {
      Users2x.updateLastUserActivity(liveMeeting.users2x, user)
    }
  }

  private def handleBbbCommonEnvCoreMsg(msg: BbbCommonEnvCoreMsg): Unit = {
    msg.core match {
      case m: ClientToServerLatencyTracerMsg => handleMessageThatDoesNotAffectsInactivity(msg)
      case _                                 => handleMessageThatAffectsInactivity(msg)
    }
  }

  private def handleMessageThatDoesNotAffectsInactivity(msg: BbbCommonEnvCoreMsg): Unit = {
    msg.core match {
      case m: ClientToServerLatencyTracerMsg => handleClientToServerLatencyTracerMsg(m)
    }
  }

  private def handleMessageThatAffectsInactivity(msg: BbbCommonEnvCoreMsg): Unit = {

    msg.core match {
      case m: EndMeetingSysCmdMsg                 => handleEndMeeting(m, state)

      // Users
      case m: ValidateAuthTokenReqMsg             => state = usersApp.handleValidateAuthTokenReqMsg(m, state)
      case m: UserJoinMeetingReqMsg               => state = handleUserJoinMeetingReqMsg(m, state)
      case m: UserJoinMeetingAfterReconnectReqMsg => state = handleUserJoinMeetingAfterReconnectReqMsg(m, state)
      case m: UserLeaveReqMsg                     => state = handleUserLeaveReqMsg(m, state)
      case m: UserBroadcastCamStartMsg            => handleUserBroadcastCamStartMsg(m)
      case m: UserBroadcastCamStopMsg             => handleUserBroadcastCamStopMsg(m)
      case m: UserJoinedVoiceConfEvtMsg           => handleUserJoinedVoiceConfEvtMsg(m)
      case m: MeetingActivityResponseCmdMsg =>
        state = usersApp.handleMeetingActivityResponseCmdMsg(m, state)
        state = updateInactivityTracker(state)
      case m: LogoutAndEndMeetingCmdMsg => usersApp.handleLogoutAndEndMeetingCmdMsg(m, state)
      case m: SetRecordingStatusCmdMsg =>
        state = usersApp.handleSetRecordingStatusCmdMsg(m, state)
        updateUserLastActivity(m.body.setBy)
      case m: RecordAndClearPreviousMarkersCmdMsg =>
        state = usersApp.handleRecordAndClearPreviousMarkersCmdMsg(m, state)
        updateUserLastActivity(m.body.setBy)
      case m: GetWebcamsOnlyForModeratorReqMsg    => usersApp.handleGetWebcamsOnlyForModeratorReqMsg(m)
      case m: UpdateWebcamsOnlyForModeratorCmdMsg => usersApp.handleUpdateWebcamsOnlyForModeratorCmdMsg(m)
      case m: GetRecordingStatusReqMsg            => usersApp.handleGetRecordingStatusReqMsg(m)
      case m: ChangeUserEmojiCmdMsg               => handleChangeUserEmojiCmdMsg(m)

      // Client requested to eject user
      case m: EjectUserFromMeetingCmdMsg =>
        usersApp.handleEjectUserFromMeetingCmdMsg(m)
        updateUserLastActivity(m.body.ejectedBy)

      // Another part of system (e.g. bbb-apps) requested to eject user.
      case m: EjectUserFromMeetingSysMsg => usersApp.handleEjectUserFromMeetingSysMsg(m)
      case m: GetUsersMeetingReqMsg      => usersApp.handleGetUsersMeetingReqMsg(m)
      case m: ChangeUserRoleCmdMsg =>
        usersApp.handleChangeUserRoleCmdMsg(m)
        updateUserLastActivity(m.body.changedBy)

      // Whiteboard
      case m: SendCursorPositionPubMsg       => wbApp.handle(m, liveMeeting, msgBus)
      case m: ClearWhiteboardPubMsg          => wbApp.handle(m, liveMeeting, msgBus)
      case m: UndoWhiteboardPubMsg           => wbApp.handle(m, liveMeeting, msgBus)
      case m: ModifyWhiteboardAccessPubMsg   => wbApp.handle(m, liveMeeting, msgBus)
      case m: SendWhiteboardAnnotationPubMsg => wbApp.handle(m, liveMeeting, msgBus)
      case m: GetWhiteboardAnnotationsReqMsg => wbApp.handle(m, liveMeeting, msgBus)

      // Poll
      case m: StartPollReqMsg =>
        pollApp.handle(m, state, liveMeeting, msgBus) // passing state but not modifying it
        updateUserLastActivity(m.body.requesterId)
      case m: StartCustomPollReqMsg =>
        pollApp.handle(m, state, liveMeeting, msgBus) // passing state but not modifying it
        updateUserLastActivity(m.body.requesterId)
      case m: StopPollReqMsg =>
        pollApp.handle(m, state, liveMeeting, msgBus) // passing state but not modifying it
        updateUserLastActivity(m.body.requesterId)
      case m: ShowPollResultReqMsg =>
        pollApp.handle(m, state, liveMeeting, msgBus) // passing state but not modifying it
        updateUserLastActivity(m.body.requesterId)
      case m: GetCurrentPollReqMsg => pollApp.handle(m, state, liveMeeting, msgBus) // passing state but not modifying it
      case m: RespondToPollReqMsg =>
        pollApp.handle(m, liveMeeting, msgBus)
        updateUserLastActivity(m.body.requesterId)

      // Breakout
      case m: BreakoutRoomsListMsg            => state = handleBreakoutRoomsListMsg(m, state)
      case m: CreateBreakoutRoomsCmdMsg       => state = handleCreateBreakoutRoomsCmdMsg(m, state)
      case m: EndAllBreakoutRoomsMsg          => state = handleEndAllBreakoutRoomsMsg(m, state)
      case m: RequestBreakoutJoinURLReqMsg    => state = handleRequestBreakoutJoinURLReqMsg(m, state)
      case m: TransferUserToMeetingRequestMsg => state = handleTransferUserToMeetingRequestMsg(m, state)

      // Voice
      case m: UserLeftVoiceConfEvtMsg         => handleUserLeftVoiceConfEvtMsg(m)
      case m: UserMutedInVoiceConfEvtMsg      => handleUserMutedInVoiceConfEvtMsg(m)
      case m: UserTalkingInVoiceConfEvtMsg =>
        state = updateInactivityTracker(state)
        updateVoiceUserLastActivity(m.body.voiceUserId)
        handleUserTalkingInVoiceConfEvtMsg(m)
      case m: VoiceConfCallStateEvtMsg        => handleVoiceConfCallStateEvtMsg(m)

      case m: RecordingStartedVoiceConfEvtMsg => handleRecordingStartedVoiceConfEvtMsg(m)
      case m: MuteUserCmdMsg =>
        usersApp.handleMuteUserCmdMsg(m)
        updateUserLastActivity(m.body.mutedBy)
      case m: MuteAllExceptPresentersCmdMsg =>
        handleMuteAllExceptPresentersCmdMsg(m)
        updateUserLastActivity(m.body.mutedBy)
      case m: EjectUserFromVoiceCmdMsg => handleEjectUserFromVoiceCmdMsg(m)
      case m: IsMeetingMutedReqMsg     => handleIsMeetingMutedReqMsg(m)
      case m: MuteMeetingCmdMsg =>
        handleMuteMeetingCmdMsg(m)
        updateUserLastActivity(m.body.mutedBy)
      case m: UserConnectedToGlobalAudioMsg      => handleUserConnectedToGlobalAudioMsg(m)
      case m: UserDisconnectedFromGlobalAudioMsg => handleUserDisconnectedFromGlobalAudioMsg(m)
      case m: VoiceConfRunningEvtMsg             => handleVoiceConfRunningEvtMsg(m)
      case m: CheckRunningAndRecordingVoiceConfEvtMsg =>
        handleCheckRunningAndRecordingVoiceConfEvtMsg(m)
      case m: UserStatusVoiceConfEvtMsg =>
        handleUserStatusVoiceConfEvtMsg(m)

      // Layout
      case m: GetCurrentLayoutReqMsg => handleGetCurrentLayoutReqMsg(m)
      case m: BroadcastLayoutMsg     => handleBroadcastLayoutMsg(m)

      // Lock Settings
      case m: ChangeLockSettingsInMeetingCmdMsg =>
        handleSetLockSettings(m)
        updateUserLastActivity(m.body.setBy)
      case m: LockUserInMeetingCmdMsg                  => handleLockUserInMeetingCmdMsg(m)
      case m: LockUsersInMeetingCmdMsg                 => handleLockUsersInMeetingCmdMsg(m)
      case m: GetLockSettingsReqMsg                    => handleGetLockSettingsReqMsg(m)

      // Presentation
      case m: PreuploadedPresentationsSysPubMsg        => presentationApp2x.handle(m, liveMeeting, msgBus)
      case m: AssignPresenterReqMsg                    => state = handlePresenterChange(m, state)

      // Presentation Pods
      case m: CreateNewPresentationPodPubMsg           => state = presentationPodsApp.handle(m, state, liveMeeting, msgBus)
      case m: RemovePresentationPodPubMsg              => state = presentationPodsApp.handle(m, state, liveMeeting, msgBus)
      case m: GetAllPresentationPodsReqMsg             => state = presentationPodsApp.handle(m, state, liveMeeting, msgBus)
      case m: SetCurrentPresentationPubMsg             => state = presentationPodsApp.handle(m, state, liveMeeting, msgBus)
      case m: PresentationConversionCompletedSysPubMsg => state = presentationPodsApp.handle(m, state, liveMeeting, msgBus)
      case m: PdfConversionInvalidErrorSysPubMsg       => state = presentationPodsApp.handle(m, state, liveMeeting, msgBus)
      case m: SetCurrentPagePubMsg                     => state = presentationPodsApp.handle(m, state, liveMeeting, msgBus)
      case m: SetPresenterInPodReqMsg                  => state = presentationPodsApp.handle(m, state, liveMeeting, msgBus)
      case m: RemovePresentationPubMsg                 => state = presentationPodsApp.handle(m, state, liveMeeting, msgBus)
      case m: SetPresentationDownloadablePubMsg        => state = presentationPodsApp.handle(m, state, liveMeeting, msgBus)
      case m: PresentationConversionUpdateSysPubMsg    => state = presentationPodsApp.handle(m, state, liveMeeting, msgBus)
      case m: PresentationPageGeneratedSysPubMsg       => state = presentationPodsApp.handle(m, state, liveMeeting, msgBus)
      case m: PresentationPageCountErrorSysPubMsg      => state = presentationPodsApp.handle(m, state, liveMeeting, msgBus)
      case m: PresentationUploadTokenReqMsg            => state = presentationPodsApp.handle(m, state, liveMeeting, msgBus)
      case m: ResizeAndMovePagePubMsg                  => state = presentationPodsApp.handle(m, state, liveMeeting, msgBus)
      case m: PresentationPageConvertedSysMsg          => state = presentationPodsApp.handle(m, state, liveMeeting, msgBus)
      case m: PresentationPageConversionStartedSysMsg  => state = presentationPodsApp.handle(m, state, liveMeeting, msgBus)
      case m: PresentationConversionEndedSysMsg        => state = presentationPodsApp.handle(m, state, liveMeeting, msgBus)

      // Caption
      case m: EditCaptionHistoryPubMsg                 => captionApp2x.handle(m, liveMeeting, msgBus)
      case m: UpdateCaptionOwnerPubMsg                 => captionApp2x.handle(m, liveMeeting, msgBus)
      case m: SendCaptionHistoryReqMsg                 => captionApp2x.handle(m, liveMeeting, msgBus)

      // SharedNotes
      case m: GetSharedNotesPubMsg                     => sharedNotesApp2x.handle(m, liveMeeting, msgBus)
      case m: SyncSharedNotePubMsg                     => sharedNotesApp2x.handle(m, liveMeeting, msgBus)
      case m: ClearSharedNotePubMsg                    => sharedNotesApp2x.handle(m, liveMeeting, msgBus)
      case m: UpdateSharedNoteReqMsg                   => sharedNotesApp2x.handle(m, liveMeeting, msgBus)
      case m: CreateSharedNoteReqMsg                   => sharedNotesApp2x.handle(m, liveMeeting, msgBus)
      case m: DestroySharedNoteReqMsg                  => sharedNotesApp2x.handle(m, liveMeeting, msgBus)

      // Guests
      case m: GetGuestsWaitingApprovalReqMsg           => handleGetGuestsWaitingApprovalReqMsg(m)
      case m: SetGuestPolicyCmdMsg                     => handleSetGuestPolicyMsg(m)
      case m: GuestsWaitingApprovedMsg                 => handleGuestsWaitingApprovedMsg(m)
      case m: GetGuestPolicyReqMsg                     => handleGetGuestPolicyReqMsg(m)

      // Chat
      case m: GetChatHistoryReqMsg                     => chatApp2x.handle(m, liveMeeting, msgBus)
      case m: SendPublicMessagePubMsg =>
        chatApp2x.handle(m, liveMeeting, msgBus)
        updateUserLastActivity(m.body.message.fromUserId)
      case m: SendPrivateMessagePubMsg =>
        chatApp2x.handle(m, liveMeeting, msgBus)
        updateUserLastActivity(m.body.message.fromUserId)
      case m: ClearPublicChatHistoryPubMsg                   => state = chatApp2x.handle(m, state, liveMeeting, msgBus)
      case m: UserTypingPubMsg                               => chatApp2x.handle(m, liveMeeting, msgBus)

      // Screenshare
      case m: ScreenshareStartedVoiceConfEvtMsg              => screenshareApp2x.handle(m, liveMeeting, msgBus)
      case m: ScreenshareStoppedVoiceConfEvtMsg              => screenshareApp2x.handle(m, liveMeeting, msgBus)
      case m: ScreenshareRtmpBroadcastStartedVoiceConfEvtMsg => screenshareApp2x.handle(m, liveMeeting, msgBus)
      case m: ScreenshareRtmpBroadcastStoppedVoiceConfEvtMsg => screenshareApp2x.handle(m, liveMeeting, msgBus)
      case m: GetScreenshareStatusReqMsg                     => screenshareApp2x.handle(m, liveMeeting, msgBus)

      // GroupChat
      case m: CreateGroupChatReqMsg =>
        state = groupChatApp.handle(m, state, liveMeeting, msgBus)
        updateUserLastActivity(m.header.userId)
      case m: GetGroupChatMsgsReqMsg => state = groupChatApp.handle(m, state, liveMeeting, msgBus)
      case m: GetGroupChatsReqMsg    => state = groupChatApp.handle(m, state, liveMeeting, msgBus)
      case m: SendGroupChatMessageMsg =>
        state = groupChatApp.handle(m, state, liveMeeting, msgBus)
        updateUserLastActivity(m.body.msg.sender.id)

      case m: ValidateConnAuthTokenSysMsg => handleValidateConnAuthTokenSysMsg(m)

      case m: UserActivitySignCmdMsg      => handleUserActivitySignCmdMsg(m)

      case _                              => log.warning("***** Cannot handle " + msg.envelope.name)
    }
  }

  def handleGetAllMeetingsReqMsg(msg: GetAllMeetingsReqMsg): Unit = {
    // sync all meetings
    handleSyncGetMeetingInfoRespMsg(liveMeeting.props)

    // sync all users
    usersApp.handleSyncGetUsersMeetingRespMsg()

    // sync all presentations
    presentationPodsApp.handleSyncGetPresentationPods(state, liveMeeting, msgBus)

    // sync all group chats and group chat messages
    groupChatApp.handleSyncGetGroupChatsInfo(state, liveMeeting, msgBus)

    // sync all voice users
    handleSyncGetVoiceUsersMsg(state, liveMeeting, msgBus)

    // sync all lock settings
    handleSyncGetLockSettingsMsg(state, liveMeeting, msgBus)

    // TODO send all screen sharing info

  }

  def handlePresenterChange(msg: AssignPresenterReqMsg, state: MeetingState2x): MeetingState2x = {
    // Stop poll if one is running as presenter left
    pollApp.stopPoll(state, msg.header.userId, liveMeeting, msgBus)

    // switch user presenter status for old and new presenter
    val newState = usersApp.handleAssignPresenterReqMsg(msg, state)

    // request screenshare to end
    screenshareApp2x.handleScreenshareStoppedVoiceConfEvtMsg(
      liveMeeting.props.voiceProp.voiceConf,
      liveMeeting.props.screenshareProps.screenshareConf,
      liveMeeting, msgBus
    )

    newState

  }

  def handleDeskShareGetDeskShareInfoRequest(msg: DeskShareGetDeskShareInfoRequest): Unit = {

    log.info("handleDeskShareGetDeskShareInfoRequest: " + msg.conferenceName + "isBroadcasting="
      + ScreenshareModel.isBroadcastingRTMP(liveMeeting.screenshareModel) + " URL:" +
      ScreenshareModel.getRTMPBroadcastingUrl(liveMeeting.screenshareModel))

    if (ScreenshareModel.isBroadcastingRTMP(liveMeeting.screenshareModel)) {
      // if the meeting has an ongoing WebRTC Deskshare session, send a notification
      //outGW.send(new DeskShareNotifyASingleViewer(props.meetingProp.intId, msg.requesterID,
      //  DeskshareModel.getRTMPBroadcastingUrl(liveMeeting.deskshareModel),
      //  DeskshareModel.getDesktopShareVideoWidth(liveMeeting.deskshareModel),
      //  DeskshareModel.getDesktopShareVideoHeight(liveMeeting.deskshareModel), true))
    }
  }

  def handleMonitorNumberOfUsers(msg: MonitorNumberOfUsersInternalMsg) {
    state = removeUsersWithExpiredUserLeftFlag(liveMeeting, state)

    val (newState, expireReason) = ExpiryTrackerHelper.processMeetingInactivityAudit(outGW, eventBus, liveMeeting, state)
    state = newState
    expireReason foreach (reason => log.info("Meeting {} expired with reason {}", props.meetingProp.intId, reason))
    val (newState2, expireReason2) = ExpiryTrackerHelper.processMeetingExpiryAudit(outGW, eventBus, liveMeeting, state)
    state = newState2
    expireReason2 foreach (reason => log.info("Meeting {} expired with reason {}", props.meetingProp.intId, reason))

    sendRttTraceTest()
    setRecordingChapterBreak()

    processUserInactivityAudit()
    flagRegisteredUsersWhoHasNotJoined()
    checkIfNeetToEndMeetingWhenNoAuthedUsers(liveMeeting)
  }

  def checkVoiceConfUsersStatus(): Unit = {
    val event = MsgBuilder.buildLastcheckVoiceConfUsersStatus(
      props.meetingProp.intId,
      props.voiceProp.voiceConf
    )
    outGW.send(event)
  }

  def checkVoiceConfIsRunningAndRecording(): Unit = {
    val event = MsgBuilder.buildCheckRunningAndRecordingToVoiceConfSysMsg(
      props.meetingProp.intId,
      props.voiceProp.voiceConf
    )
    outGW.send(event)
  }

  var lastRecBreakSentOn = expiryTracker.startedOnInMs

  def setRecordingChapterBreak(): Unit = {
    val now = TimeUtil.timeNowInMs()
    val elapsedInMs = now - lastRecBreakSentOn
    val elapsedInMin = TimeUtil.millisToMinutes(elapsedInMs)

    if (props.recordProp.record &&
      recordingChapterBreakLengthInMinutes > 0 &&
      elapsedInMin > recordingChapterBreakLengthInMinutes) {
      lastRecBreakSentOn = now
      val event = MsgBuilder.buildRecordingChapterBreakSysMsg(props.meetingProp.intId, TimeUtil.timeNowInMs())
      outGW.send(event)

      VoiceApp.stopRecordingVoiceConference(liveMeeting, outGW)

      val meetingId = liveMeeting.props.meetingProp.intId
      val recordFile = VoiceApp.genRecordPath(
        voiceConfRecordPath,
        meetingId,
        now,
        voiceConfRecordCodec
      )
      VoiceApp.startRecordingVoiceConference(liveMeeting, outGW, recordFile)
    }
  }

  def sendRttTraceTest(): Unit = {
    val now = System.currentTimeMillis()

    def buildDoLatencyTracerMsg(meetingId: String): BbbCommonEnvCoreMsg = {
      val routing = Routing.addMsgToClientRouting(MessageTypes.BROADCAST_TO_MEETING, meetingId, "not-used")
      val envelope = BbbCoreEnvelope(DoLatencyTracerMsg.NAME, routing)
      val body = DoLatencyTracerMsgBody(now)
      val header = BbbClientMsgHeader(DoLatencyTracerMsg.NAME, meetingId, "not-used")
      val event = DoLatencyTracerMsg(header, body)

      BbbCommonEnvCoreMsg(envelope, event)
    }

    if (now - lastRttTestSentOn > 60000) {
      lastRttTestSentOn = now
      val event = buildDoLatencyTracerMsg(liveMeeting.props.meetingProp.intId)
      outGW.send(event)
    }

  }

  private def checkIfNeetToEndMeetingWhenNoAuthedUsers(liveMeeting: LiveMeeting): Unit = {
    val authUserJoined = MeetingStatus2x.hasAuthedUserJoined(liveMeeting.status)

    if (endMeetingWhenNoMoreAuthedUsers &&
      !liveMeeting.props.meetingProp.isBreakout &&
      authUserJoined) {
      val lastAuthedUserLeftLimitMs = TimeUtil.timeNowInMs() - MeetingStatus2x.getLastAuthedUserLeftOn(liveMeeting.status)
      if (lastAuthedUserLeftLimitMs > TimeUtil.minutesToMillis(endMeetingWhenNoMoreAuthedUsersAfterMinutes)) {
        val authedUsers = Users2x.findAllAuthedUsers(liveMeeting.users2x)

        if (authedUsers.isEmpty) {
          sendEndMeetingDueToExpiry(
            MeetingEndReason.ENDED_DUE_TO_NO_AUTHED_USER,
            eventBus, outGW, liveMeeting
          )
        }
      }
    }
  }

  def handleExtendMeetingDuration(msg: ExtendMeetingDuration) {

  }

  def removeUsersWithExpiredUserLeftFlag(liveMeeting: LiveMeeting, state: MeetingState2x): MeetingState2x = {
    val leftUsers = Users2x.findAllExpiredUserLeftFlags(liveMeeting.users2x, expiryTracker.meetingExpireWhenLastUserLeftInMs)
    leftUsers foreach { leftUser =>
      for {
        u <- Users2x.remove(liveMeeting.users2x, leftUser.intId)
      } yield {
        log.info("Removing user from meeting. meetingId=" + props.meetingProp.intId + " userId=" + u.intId + " user=" + u)

        captionApp2x.handleUserLeavingMsg(leftUser.intId, liveMeeting, msgBus)

        // send a user left event for the clients to update
        val userLeftMeetingEvent = MsgBuilder.buildUserLeftMeetingEvtMsg(liveMeeting.props.meetingProp.intId, u.intId)
        outGW.send(userLeftMeetingEvent)

        if (u.presenter) {
          UsersApp.automaticallyAssignPresenter(outGW, liveMeeting)

          // request screenshare to end
          screenshareApp2x.handleScreenshareStoppedVoiceConfEvtMsg(liveMeeting.props.voiceProp.voiceConf, liveMeeting.props.screenshareProps.screenshareConf, liveMeeting, msgBus)

          // request ongoing poll to end
          Polls.handleStopPollReqMsg(state, u.intId, liveMeeting)
        }
      }
    }

    stopRecordingIfAutoStart2x(outGW, liveMeeting, state)

    if (liveMeeting.props.meetingProp.isBreakout) {
      BreakoutHdlrHelpers.updateParentMeetingWithUsers(liveMeeting, eventBus)
    }

    if (state.expiryTracker.userHasJoined &&
      Users2x.numUsers(liveMeeting.users2x) == 0
      && !state.expiryTracker.lastUserLeftOnInMs.isDefined) {
      log.info("Setting meeting no more users. meetingId=" + props.meetingProp.intId)
      val tracker = state.expiryTracker.setLastUserLeftOn(TimeUtil.timeNowInMs())
      state.update(tracker)
    } else {
      state
    }
  }

  var lastUserInactivityInspectSentOn = TimeUtil.timeNowInMs()
  var checkInactiveUsers = false

  def processUserInactivityAudit(): Unit = {
    val now = TimeUtil.timeNowInMs()

    // Check if user is inactive. We only do the check is user inactivity
    // is not disabled (0).
    if ((expiryTracker.userInactivityInspectTimerInMs > 0) &&
      (now > lastUserInactivityInspectSentOn + expiryTracker.userInactivityInspectTimerInMs)) {
      lastUserInactivityInspectSentOn = now
      checkInactiveUsers = true
      warnPotentiallyInactiveUsers()
    }

    if (checkInactiveUsers && now > lastUserInactivityInspectSentOn + expiryTracker.userActivitySignResponseDelayInMs) {
      checkInactiveUsers = false
      disconnectInactiveUsers()
    }
  }

  def warnPotentiallyInactiveUsers(): Unit = {
    log.info("Checking for inactive users.")
    val users = Users2x.findAll(liveMeeting.users2x)
    users foreach { u =>
      val active = (lastUserInactivityInspectSentOn - expiryTracker.userInactivityThresholdInMs) < u.lastActivityTime
      if (!active) {
        Sender.sendUserInactivityInspectMsg(liveMeeting.props.meetingProp.intId, u.intId, TimeUtil.minutesToSeconds(props.durationProps.userActivitySignResponseDelayInMinutes), outGW)
      }
    }
  }

  def disconnectInactiveUsers(): Unit = {
    log.info("Check for users who haven't responded to user inactivity warning.")
    val users = Users2x.findAll(liveMeeting.users2x)
    users foreach { u =>
      val respondedOnTime = (lastUserInactivityInspectSentOn - expiryTracker.userInactivityThresholdInMs) < u.lastActivityTime && (lastUserInactivityInspectSentOn + expiryTracker.userActivitySignResponseDelayInMs) > u.lastActivityTime
      if (!respondedOnTime) {
        UsersApp.ejectUserFromMeeting(
          outGW,
          liveMeeting,
          u.intId,
          SystemUser.ID,
          "User inactive for too long.",
          EjectReasonCode.USER_INACTIVITY,
          ban = false
        )

        Sender.sendDisconnectClientSysMsg(liveMeeting.props.meetingProp.intId, u.intId, SystemUser.ID, EjectReasonCode.USER_INACTIVITY, outGW)
      }
    }
  }

  def flagRegisteredUsersWhoHasNotJoined(): Unit = {
    val users = RegisteredUsers.findUsersNotJoined(liveMeeting.registeredUsers)
    users foreach { u =>
      val now = System.currentTimeMillis()
      if (now - u.registeredOn > TimeUtil.secondsToMillis(maxRegUserToJoinTime)) {
        RegisteredUsers.markAsUserFailedToJoin(liveMeeting.registeredUsers, u)
        val event = MsgBuilder.buildRegisteredUserJoinTimeoutMsg(
          liveMeeting.props.meetingProp.intId,
          u.id,
          u.name
        )
        outGW.send(event)
      }
    }
  }

  def handleCheckRunningAndRecordingVoiceConfEvtMsg(msg: CheckRunningAndRecordingVoiceConfEvtMsg): Unit = {
    //msg.body.confRecordings foreach { cr =>
    //  println("rec = " + cr.recordPath)
    //}

    if (liveMeeting.props.recordProp.record &&
      msg.body.isRunning &&
      !msg.body.isRecording) {
      // Voice conference is running but not recording. We should start recording.
      // But first, see if we have recording streams and stop those.
      VoiceApp.stopRecordingVoiceConference(liveMeeting, outGW)
      // Remove recording streams that have stopped so we should only have
      // one active recording stream.

      // Let us start recording.
      val meetingId = liveMeeting.props.meetingProp.intId
      val recordFile = VoiceApp.genRecordPath(
        voiceConfRecordPath,
        meetingId,
        TimeUtil.timeNowInMs(),
        voiceConfRecordCodec
      )
      log.info("Forcing START RECORDING voice conf. meetingId=" + meetingId + " voice conf=" + liveMeeting.props.voiceProp.voiceConf)

      VoiceApp.startRecordingVoiceConference(liveMeeting, outGW, recordFile)
    }
  }

}
