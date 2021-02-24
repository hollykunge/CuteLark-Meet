package org.bigbluebutton.air.main.commands {
	import mx.core.FlexGlobals;
	
	import spark.components.Application;
	
	import org.bigbluebutton.air.main.views.UserInactivityPopUp;
	
	import robotlegs.bender.bundles.mvcs.Command;
	
	public class UserInactivityTimerCommand extends Command {
		
		[Inject]
		public var responseDuration:Number
		
		override public function execute():void {
			trace("RECEIVED INACTIVITY TIMER MESSAGE responseDuration=" + responseDuration);
			var userInactivityView:UserInactivityPopUp = new UserInactivityPopUp();
			userInactivityView.text = "Your client is going to close in " + responseDuration.toString() + " seconds";
			userInactivityView.open(Application(FlexGlobals.topLevelApplication), true);
		}
	}
}
