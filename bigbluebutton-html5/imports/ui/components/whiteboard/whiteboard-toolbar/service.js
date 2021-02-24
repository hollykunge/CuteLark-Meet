import { makeCall } from '/imports/ui/services/api';
import Storage from '/imports/ui/services/storage/session';
import Users from '/imports/api/users';
import Auth from '/imports/ui/services/auth';
import WhiteboardMultiUser from '/imports/api/whiteboard-multi-user/';
import getFromUserSettings from '/imports/ui/services/users-settings';

const DRAW_SETTINGS = 'drawSettings';
const WHITEBOARD_TOOLBAR = Meteor.settings.public.whiteboard.toolbar;

const makeSetter = key => (value) => {
  const drawSettings = Storage.getItem(DRAW_SETTINGS);
  if (drawSettings) {
    drawSettings[key] = value;
    Storage.setItem(DRAW_SETTINGS, drawSettings);
  }
};

const undoAnnotation = (whiteboardId) => {
  makeCall('undoAnnotation', whiteboardId);
};

const clearWhiteboard = (whiteboardId) => {
  makeCall('clearWhiteboard', whiteboardId);
};

const changeWhiteboardMode = (multiUser, whiteboardId) => {
  makeCall('changeWhiteboardAccess', multiUser, whiteboardId);
};

const setInitialWhiteboardToolbarValues = (tool, thickness, color, fontSize, textShape) => {
  const _drawSettings = Storage.getItem(DRAW_SETTINGS);
  if (!_drawSettings) {
    const drawSettings = {
      whiteboardAnnotationTool: tool,
      whiteboardAnnotationThickness: thickness,
      whiteboardAnnotationColor: color,
      textFontSize: fontSize,
      textShape,
    };
    Storage.setItem(DRAW_SETTINGS, drawSettings);
  }
};

const getCurrentDrawSettings = () => Storage.getItem(DRAW_SETTINGS);

const setFontSize = makeSetter('textFontSize');

const setTool = makeSetter('whiteboardAnnotationTool');

const setThickness = makeSetter('whiteboardAnnotationThickness');

const setColor = makeSetter('whiteboardAnnotationColor');

const setTextShapeObject = makeSetter('textShape');

const getTextShapeActiveId = () => {
  const drawSettings = Storage.getItem(DRAW_SETTINGS);
  return drawSettings ? drawSettings.textShape.textShapeActiveId : '';
};

const getMultiUserStatus = (whiteboardId) => {
  const data = WhiteboardMultiUser.findOne({ meetingId: Auth.meetingID, whiteboardId });
  return data ? data.multiUser : false;
};

const isPresenter = () => {
  const currentUser = Users.findOne({ userId: Auth.userID }, { fields: { presenter: 1 } });
  return currentUser ? currentUser.presenter : false;
};

const filterAnnotationList = () => {
  const multiUserPenOnly = getFromUserSettings('bbb_multi_user_pen_only', WHITEBOARD_TOOLBAR.multiUserPenOnly);

  let filteredAnnotationList = WHITEBOARD_TOOLBAR.tools;

  if (!isPresenter() && multiUserPenOnly) {
    filteredAnnotationList = [{
      icon: 'pen_tool',
      value: 'pencil',
    }];
  }

  const presenterTools = getFromUserSettings('bbb_presenter_tools', WHITEBOARD_TOOLBAR.presenterTools);
  if (isPresenter() && Array.isArray(presenterTools)) {
    filteredAnnotationList = WHITEBOARD_TOOLBAR.tools.filter(el =>
      presenterTools.includes(el.value));
  }

  const multiUserTools = getFromUserSettings('bbb_multi_user_tools', WHITEBOARD_TOOLBAR.multiUserTools);
  if (!isPresenter() && !multiUserPenOnly && Array.isArray(multiUserTools)) {
    filteredAnnotationList = WHITEBOARD_TOOLBAR.tools.filter(el =>
      multiUserTools.includes(el.value));
  }

  return filteredAnnotationList;
};

export default {
  undoAnnotation,
  clearWhiteboard,
  changeWhiteboardMode,
  setInitialWhiteboardToolbarValues,
  getCurrentDrawSettings,
  setFontSize,
  setTool,
  setThickness,
  setColor,
  setTextShapeObject,
  getTextShapeActiveId,
  getMultiUserStatus,
  isPresenter,
  filterAnnotationList,
};
