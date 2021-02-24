package org.bigbluebutton.air.settings.views.camera {
	
	import flash.events.Event;
	import flash.events.MouseEvent;
	import flash.geom.Matrix;
	import flash.geom.Point;
	import flash.media.Camera;
	import flash.media.CameraPosition;
	import flash.media.Video;
	
	import org.bigbluebutton.air.common.PageEnum;
	import org.bigbluebutton.air.main.models.IUISession;
	import org.bigbluebutton.air.user.models.User2x;
	import org.bigbluebutton.air.video.commands.ShareCameraSignal;
	import org.bigbluebutton.air.video.models.VideoProfile;
	
	public class CameraSettingsViewMediatorAIR extends CameraSettingsViewMediatorBase {
		
		[Inject]
		public var userUISession:IUISession;
		
		[Inject]
		public var shareCameraSignal:ShareCameraSignal;
		
		override public function initialize():void {
			super.initialize();
			//var userMe:User = userSession.userList.me;
			if (Camera.getCamera() == null) {
				// view.startCameraButton.label = ResourceManager.getInstance().getString('resources', 'profile.settings.camera.unavailable');
				// view.startCameraButton.enabled = false;
			} else {
				// view.startCameraButton.label = ResourceManager.getInstance().getString('resources', userMe.hasStream ? 'profile.settings.camera.on' : 'profile.settings.camera.off');
				// view.startCameraButton.enabled = true;
			}
			if (Camera.names.length <= 1) {
				setSwapCameraButtonEnable(false);
			} else {
				//setSwapCameraButtonEnable(!userMe.hasStream);
				view.swapCameraButton.addEventListener(MouseEvent.CLICK, mouseClickHandler);
			}
						
			//setRotateCameraButtonEnable(!userMe.hasStream);
			// FlexGlobals.topLevelApplication.stage.addEventListener(ResizeEvent.RESIZE, stageOrientationChangingHandler);
			// view.startCameraButton.addEventListener(MouseEvent.CLICK, onShareCameraClick);
			view.rotateCameraButton.addEventListener(MouseEvent.CLICK, onRotateCameraClick);
			// FlexGlobals.topLevelApplication.topActionBar.pageName.text = ResourceManager.getInstance().getString('resources', 'cameraSettings.title');
			displayPreviewCamera();
		}
		
		private function stageOrientationChangingHandler(e:Event):void {
			userUISession.popPage();
			userUISession.pushPage(PageEnum.CAMERASETTINGS);
		}
		
		
		override protected function userChangeHandler(user:User2x, type:int):void {
			/*
			if (user.me) {
				if (type == UserList.HAS_STREAM) {
					// view.startCameraButton.label = ResourceManager.getInstance().getString('resources', user.hasStream ? 'profile.settings.camera.on' : 'profile.settings.camera.off');
					view.rotateCameraButton.enabled = !user.hasStream;
					if (Camera.names.length > 1) {
						setSwapCameraButtonEnable(true)
					}
				}
			}
			*/
		}
		
		protected function onShareCameraClick(event:MouseEvent):void {
			/*
			setRotateCameraButtonEnable(userSession.userList.me.hasStream);
			setQualityListEnable(userSession.userList.me.hasStream);
			view.cameraProfilesList.selectedIndex = dataProvider.getItemIndex(userSession.videoConnection.selectedCameraQuality);
			shareCameraSignal.dispatch(!userSession.userList.me.hasStream, userSession.videoConnection.cameraPosition);
			displayPreviewCamera();
			if (userSession.videoAutoStart && !userSession.skipCamSettingsCheck) {
				userSession.videoAutoStart = false;
				userUISession.popPage();
			}
			*/
		}
		
		protected function onRotateCameraClick(event:MouseEvent):void {
			userSession.videoConnection.selectedCameraRotation += 90;
			if (userSession.videoConnection.selectedCameraRotation == 360) {
				userSession.videoConnection.selectedCameraRotation = 0;
			}
			saveData.save("cameraRotation", userSession.videoConnection.selectedCameraRotation);
			
			//trace("CAMERA ROTATION = " + userSession.videoConnection.selectedCameraRotation);
			
			displayPreviewCamera();
		}
		
		protected function setSwapCameraButtonEnable(enabled:Boolean):void {
			view.swapCameraButton.enabled = enabled;
		}
		
		protected function setRotateCameraButtonEnable(enabled:Boolean):void {
			view.rotateCameraButton.enabled = enabled;
		}
		
		private function isCamRotatedSideways():Boolean {
			return (userSession.videoConnection.selectedCameraRotation == 90 || userSession.videoConnection.selectedCameraRotation == 270);
		}
		
		override protected function displayPreviewCamera():void {
			//trace("DISPLAY PREVIEW CAMERA = " + userSession.videoConnection.selectedCameraRotation);
			
			var profile:VideoProfile = userSession.videoConnection.selectedCameraQuality;
			var camera:Camera = getCamera(userSession.videoConnection.cameraPosition);
			
			if (camera && profile) {
				var myCam:Video = new Video();
				
				//trace("Orientation chW=" + view.cameraHolder.width + " chH=" + view.cameraHolder.height 
				//	+ " profileW=" + profile.width + " profileH=" + profile.height);
				
				var screenAspectRatio:Number = (view.cameraHolder.width / profile.width) / (view.cameraHolder.height / profile.height);
				
				if (screenAspectRatio > 1) { //landscape
					//trace("DISPLAY PREVIEW CAMERA LANDSCAPE screenAspectRatio=" + screenAspectRatio);
					myCam.height = view.cameraHolder.height;
					myCam.width = profile.width * view.cameraHolder.height / profile.height;
				} else { //portrait
					//trace("DISPLAY PREVIEW CAMERA PORTRAIT screenAspectRatio=" + screenAspectRatio);
					myCam.width = view.cameraHolder.width;
					myCam.height = profile.height * view.cameraHolder.width / profile.width;
				}
				
				//trace("A0 MyCam x=" + myCam.x + " y=" + myCam.y + " w=" + myCam.width + " h=" + myCam.height);
				
				if (isCamRotatedSideways()) {
					camera.setMode(profile.height, profile.width, profile.modeFps);
					var temp:Number = myCam.width;
					myCam.width = myCam.height;
					myCam.height = temp;
				} else {
					camera.setMode(profile.width, profile.height, profile.modeFps);
				}
				
				//trace("B0 MyCam x=" + myCam.x + " y=" + myCam.y + " w=" + myCam.width + " h=" + myCam.height);
				
				rotateObjectAroundInternalPoint(myCam, myCam.x + myCam.width / 2, myCam.y + myCam.height / 2, 
					userSession.videoConnection.selectedCameraRotation);
				
				//trace("C0 MyCam x=" + myCam.x + " y=" + myCam.y + " w=" + myCam.width + " h=" + myCam.height);
				
				myCam.x = (view.cameraHolder.width - myCam.width) / 2;
				
				//trace("D0 MyCam x=" + myCam.x + " y=" + myCam.y + " w=" + myCam.width + " h=" + myCam.height);
				
				if (userSession.videoConnection.selectedCameraRotation == 90) {
					myCam.x = (view.cameraHolder.width + myCam.width) / 2;
				} else if (userSession.videoConnection.selectedCameraRotation == 270) {
					myCam.y = myCam.height;
				} else if (userSession.videoConnection.selectedCameraRotation == 180) {
					myCam.x = (view.cameraHolder.width + myCam.width) / 2;
					myCam.y = myCam.height;
				}
				
				//trace("E0 MyCam x=" + myCam.x + " y=" + myCam.y + " w=" + myCam.width + " h=" + myCam.height);
				
				myCam.attachCamera(camera);
				
				view.previewVideo.removeChildren();
				
				//trace("MyCam x=" + myCam.x + " y=" + myCam.y + " w=" + myCam.width + " h=" + myCam.height);
				
				view.previewVideo.addChild(myCam);
					// view.settingsGroup.y = myCam.height;
			} else {
				view.noVideoMessage.visible = true;
			}
			
			view.positionActionButtons();
		}
		
		public static function rotateObjectAroundInternalPoint(ob:Object, x:Number, y:Number, angleDegrees:Number):void {
			var point:Point = new Point(x, y);
			var m:Matrix = ob.transform.matrix;
			point = m.transformPoint(point);
			m.tx -= point.x;
			m.ty -= point.y;
			m.rotate(angleDegrees * (Math.PI / 180));
			m.tx += point.x;
			m.ty += point.y;
			ob.transform.matrix = m;
		}
		
		private function getCamera(position:String):Camera {
			for (var i:uint = 0; i < Camera.names.length; ++i) {
				var cam:Camera = Camera.getCamera(String(i));
				if (cam.position == position)
					return cam;
			}
			return Camera.getCamera();
		}
		
		/**
		 * Raised on button click, will send signal to swap camera source
		 */
		//close old stream on swap
		private function mouseClickHandler(e:MouseEvent):void {
			var myWebcams:Array = meetingData.webcams.findWebcamsByUserId(meetingData.users.me.intId);
			var isPublishing:Boolean = myWebcams.length > 0;
			
			if (!isPublishing) {
				if (String(userSession.videoConnection.cameraPosition) == CameraPosition.FRONT) {
					userSession.videoConnection.cameraPosition = CameraPosition.BACK;
				} else {
					userSession.videoConnection.cameraPosition = CameraPosition.FRONT;
				}
			} else {
				if (String(userSession.videoConnection.cameraPosition) == CameraPosition.FRONT) {
					shareCameraSignal.dispatch(!isPublishing, CameraPosition.FRONT);
					shareCameraSignal.dispatch(isPublishing, CameraPosition.BACK);
				} else {
					shareCameraSignal.dispatch(!isPublishing, CameraPosition.BACK);
					shareCameraSignal.dispatch(isPublishing, CameraPosition.FRONT);
				}
			}
			saveData.save("cameraPosition", userSession.videoConnection.cameraPosition);
			displayPreviewCamera();
		}
		
		override public function destroy():void {
			super.destroy();
			if (Camera.names.length > 1) {
				view.swapCameraButton.removeEventListener(MouseEvent.CLICK, mouseClickHandler);
			}
			// FlexGlobals.topLevelApplication.stage.removeEventListener(ResizeEvent.RESIZE, stageOrientationChangingHandler);
			// view.startCameraButton.removeEventListener(MouseEvent.CLICK, onShareCameraClick);
			view.rotateCameraButton.removeEventListener(MouseEvent.CLICK, onRotateCameraClick);
			// view.dispose();
			view = null;
			userSession.videoAutoStart = false;
		}
	}
}
