/**
 * BigBlueButton open source conferencing system - http://www.bigbluebutton.org/
 * 
 * Copyright (c) 2012 BigBlueButton Inc. and by respective authors (see below).
 *
 * This program is free software; you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free Software
 * Foundation; either version 3.0 of the License, or (at your option) any later
 * version.
 * 
 * BigBlueButton is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along
 * with BigBlueButton; if not, see <http://www.gnu.org/licenses/>.
 *
 */
package org.bigbluebutton.modules.chat.model
{
  import com.adobe.utils.StringUtil;
  import com.asfusion.mate.events.Dispatcher;
  import flash.external.ExternalInterface;
  import flash.system.Capabilities;
  import mx.collections.ArrayCollection;
	import org.as3commons.logging.api.ILogger;
	import org.as3commons.logging.api.getClassLogger;
  import org.as3commons.lang.StringUtils;
  import org.bigbluebutton.common.Role;
  import org.bigbluebutton.core.UsersUtil;
  import org.bigbluebutton.core.model.LiveMeeting;
  import org.bigbluebutton.modules.chat.ChatUtil;
  import org.bigbluebutton.modules.chat.events.ChatHistoryEvent;
  import org.bigbluebutton.modules.chat.vo.ChatMessageVO;
  import org.bigbluebutton.util.i18n.ResourceUtil;
  
  public class ChatConversation
  { 
		private static const LOGGER:ILogger = getClassLogger(ChatConversation);
		
    private var _dispatcher:Dispatcher = new Dispatcher();
    
    [Bindable]
    public var messages:ArrayCollection = new ArrayCollection();
    
    private var chatId: String;
    
		private var welcomeMsgAdded:Boolean = false;
		private var modOnlyMsgAdded:Boolean = false;
		private var howToCloseMsgAdded:Boolean = false;
		
    public function ChatConversation(chatId: String) {
      this.chatId = chatId;
    }
    
    public function getChatId(): String {
      return chatId;
    }
    
    public function numMessages():int {
      return messages.length;
    }
    
    public function newChatMessage(msg:ChatMessageVO):void {
			var previousCM:ChatMessage = null;
			if (messages.length > 0) {
				previousCM= messages.getItemAt(messages.length-1) as ChatMessage;
			}
			
      var newCM:ChatMessage = convertChatMessage(msg, previousCM);

      messages.addItem(newCM);
    }
    
    public function newPrivateChatMessage(msg:ChatMessageVO):void {
			var previousCM:ChatMessage = null;
			if (messages.length > 0) {
				previousCM = messages.getItemAt(messages.length-1) as ChatMessage;
			}
			
      var newCM:ChatMessage = convertChatMessage(msg, previousCM);
	  messages.addItem(newCM);
	  messages.refresh();
    }
    
		public function resetFlags():void {
			welcomeMsgAdded = false;
			modOnlyMsgAdded = false;
			howToCloseMsgAdded = false;
		}
		
    public function processChatHistory(messageVOs:Array):void {
      if (messageVOs.length > 0) {
				// Need to re-initialize our message collection as client might have
				// auto-reconnected (ralam dec 19, 2017)
        messages = new ArrayCollection();
				resetFlags();
        var previousCM:ChatMessage = convertChatMessage(messageVOs[0] as ChatMessageVO, null);
        var newCM:ChatMessage;
        messages.addItemAt(previousCM, 0);
        
        for (var i:int=1; i < messageVOs.length; i++) {
          newCM = convertChatMessage(messageVOs[i] as ChatMessageVO, previousCM);
          messages.addItemAt(newCM, i);
          previousCM = newCM;
        }
      }
			
			LOGGER.debug("CHAT HISTORY ----- PROCESS CHAT HISTORY [" + chatId + "]");
			
			var groupChat: GroupChat = LiveMeeting.inst().chats.getGroupChat(chatId);
			if (groupChat != null) {
				if (groupChat.access == GroupChat.PRIVATE) {
					displayHowToCloseMessage();
				} else {
					LOGGER.debug("CHAT HISTORY ----- PROCESS CHAT HISTORY [" + chatId + "] PUBLIC GROUP CHAT");
					if (chatId == ChatModel.MAIN_PUBLIC_CHAT) {
						LOGGER.debug("CHAT HISTORY ----- PROCESS CHAT HISTORY 2");
						sendWelcomeMessage(chatId);
						addModOnlyMessage();
					}
				}
			} else {
				LOGGER.debug("CHAT HISTORY ----- PROCESS CHAT HISTORY [" + chatId + "] CANNOT FIND GROUP CHAT");
			}
			
			messages.refresh();
    }
    
    private function convertChatMessage(msgVO:ChatMessageVO, prevCM:ChatMessage):ChatMessage {
      var cm:ChatMessage = new ChatMessage();      
      cm.lastSenderId = "";
      cm.lastTime = "";
      cm.senderId = msgVO.fromUserId;
      cm.text = msgVO.message;
      cm.name = msgVO.fromUsername;
      cm.senderColor = uint(msgVO.fromColor);
      
      // Welcome message will skip time
      if (msgVO.fromTime != -1) {
        cm.fromTime = msgVO.fromTime;
        cm.time = convertTimeNumberToString(msgVO.fromTime);
      }
			
			if (prevCM != null) {
				cm.lastSenderId = prevCM.senderId;
				cm.lastTime = prevCM.time;
				cm.differentLastSenderAndTime = differentLastSenderAndTime(cm.lastTime, cm.time, 
					cm.senderId, cm.lastSenderId);
			}
			
			return cm
    }
    
		private function displayHowToCloseMessage():void {
			if (howToCloseMsgAdded) {
				return;
			} else {
				howToCloseMsgAdded = true;
			}
			
			var modifier:String = ExternalInterface.call("determineModifier");
			var keyCombo:String = modifier + String.fromCharCode(int(ResourceUtil.getInstance().getString('bbb.shortcutkey.chat.closePrivate')));
			
			var msg:ChatMessageVO = new ChatMessageVO();
			msg.fromUserId = ChatModel.HOW_TO_CLOSE_MSG;
			msg.fromUsername = ChatModel.SPACE;
			msg.fromColor = "0";
			msg.fromTime = new Date().getTime();
			msg.message = "<b><i>"+ResourceUtil.getInstance().getString('bbb.chat.private.closeMessage', [keyCombo])+"</b></i>";
			
			newChatMessage(msg);
		}
		
		private function sendWelcomeMessage(chatId:String):void {
			if (welcomeMsgAdded) {
				return;
			} else {
				welcomeMsgAdded = true;
			}
			
			var welcome:String = LiveMeeting.inst().me.welcome;
			if (welcome != "") {
				var welcomeMsg:ChatMessageVO = new ChatMessageVO();
				welcomeMsg.fromUserId = ChatModel.WELCOME_MSG;
				welcomeMsg.fromUsername = ChatModel.SPACE;
				welcomeMsg.fromColor = "86187";
				welcomeMsg.fromTime = new Date().getTime();
				welcomeMsg.message = welcome;
				
				newChatMessage(welcomeMsg);
				
				//Say that client is ready when sending the welcome message
				ExternalInterface.call("clientReady", ResourceUtil.getInstance().getString('bbb.accessibility.clientReady'));
			}	
		}
		
		private function addModOnlyMessage():void {
			if (modOnlyMsgAdded) {
				return;
			} else {
				modOnlyMsgAdded = true;
			}
			
			if (UsersUtil.amIModerator()) {
				if (LiveMeeting.inst().meeting.modOnlyMessage != null) {
					var moderatorOnlyMsg:ChatMessageVO = new ChatMessageVO();
					moderatorOnlyMsg.fromUserId = ChatModel.MOD_ONLY_MSG;
					moderatorOnlyMsg.fromUsername = ChatModel.SPACE;
					moderatorOnlyMsg.fromColor = "86187";
					moderatorOnlyMsg.fromTime = new Date().getTime();
					moderatorOnlyMsg.message = LiveMeeting.inst().meeting.modOnlyMessage;
					
					newChatMessage(moderatorOnlyMsg);
					
				}
			}
		}
		
		private function differentLastSenderAndTime(lastTime: String, time: String, 
																								senderId: String, lastSenderId: String):Boolean {
			return !(lastTime == time) || !sameLastSender(senderId, lastSenderId);
		}
		
		private function sameLastSender(senderId: String, lastSenderId: String) : Boolean {
			return StringUtils.trimToEmpty(senderId) == StringUtils.trimToEmpty(lastSenderId);
		}
		
		private function isModerator(senderId: String):Boolean {
			return UsersUtil.getUser(senderId) && UsersUtil.getUser(senderId).role == Role.MODERATOR
		}
		
    private function convertTimeNumberToString(time:Number):String {
      var sentTime:Date = new Date();
      sentTime.setTime(time);
      return ChatUtil.getHours(sentTime) + ":" + ChatUtil.getMinutes(sentTime);
    }
    
    public function getAllMessageAsString():String{
      var allText:String = "";
      var returnStr:String = (Capabilities.os.indexOf("Windows") >= 0 ? "\r\n" : "\n");
      for (var i:int = 0; i < messages.length; i++){
        var item:ChatMessage = messages.getItemAt(i) as ChatMessage;
        if (StringUtil.trim(item.name) != "") {
          allText += item.name + "\t";
        }
        allText += item.time + "\t";
        allText += item.text + returnStr;
      }
      return allText;
    }
    
    public function clearPublicChat():void {
      var cm:ChatMessage = new ChatMessage();
      cm.time = convertTimeNumberToString(new Date().time);
      cm.text = "<b><i>"+ResourceUtil.getInstance().getString('bbb.chat.clearBtn.chatMessage')+"</i></b>";
      cm.name = "";
      cm.senderColor = uint(0x000000);
      
      messages.removeAll();
      messages.addItem(cm);
	  messages.refresh();

      var welcomeEvent:ChatHistoryEvent = new ChatHistoryEvent(ChatHistoryEvent.RECEIVED_HISTORY);
      _dispatcher.dispatchEvent(welcomeEvent);
    }
  }
}
