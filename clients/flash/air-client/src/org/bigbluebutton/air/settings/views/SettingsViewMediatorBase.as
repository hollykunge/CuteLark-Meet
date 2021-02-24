package org.bigbluebutton.air.settings.views {
	import mx.collections.ArrayCollection;
	
	import spark.events.IndexChangeEvent;
	
	import org.bigbluebutton.air.main.models.IMeetingData;
	import org.bigbluebutton.air.user.utils.UserUtils;
	
	import robotlegs.bender.bundles.mvcs.Mediator;
	
	public class SettingsViewMediatorBase extends Mediator {
		
		[Inject]
		public var view:SettingsViewBase;
		
		[Inject]
		public var meetingData:IMeetingData;
		
		protected var dataProvider:ArrayCollection;
		
		override public function initialize():void {
			view.participantIcon.setInitials(UserUtils.getInitials(meetingData.users.me.name));
			view.participantIcon.setRole(meetingData.users.me.role);
			view.participantLabel.text = meetingData.users.me.name;
			/*
			view.settingsList.dataProvider = dataProvider = new ArrayCollection([{label: "Audio", icon: "icon-unmute", page: "audio"}, 
				{label: "Video", icon: "icon-video", page: "camera"}, 
				{label: "Application", icon: "icon-application", page: "chat"}, 
				{label: "Leave Session", icon: "icon-logout", page: "exit"}]);
			*/
			view.settingsList.dataProvider = dataProvider = new ArrayCollection([{label: "Video", icon: "icon-video", page: "camera"},
				{label: "Leave Session", icon: "icon-logout", page: "exit"}]);
			view.settingsList.addEventListener(IndexChangeEvent.CHANGE, onListIndexChangeEvent);
		}
		
		protected function onListIndexChangeEvent(e:IndexChangeEvent):void {
			// leave the implementation to the specific client
		}
		
		override public function destroy():void {
			view.settingsList.removeEventListener(IndexChangeEvent.CHANGE, onListIndexChangeEvent);
			
			super.destroy();
			view = null;
		}
	}
}
