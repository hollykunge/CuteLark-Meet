package org.bigbluebutton.air.video.services {
	
	import flash.events.AsyncErrorEvent;
	import flash.events.IOErrorEvent;
	import flash.events.NetStatusEvent;
	import flash.media.Camera;
	import flash.media.CameraPosition;
	import flash.net.NetConnection;
	import flash.net.NetStream;
	
	import org.bigbluebutton.air.common.models.ISaveData;
	import org.bigbluebutton.air.common.services.DefaultConnectionCallback;
	import org.bigbluebutton.air.common.services.IBaseConnection;
	import org.bigbluebutton.air.main.models.IConferenceParameters;
	import org.bigbluebutton.air.main.models.IMeetingData;
	import org.bigbluebutton.air.main.models.IUserSession;
	import org.bigbluebutton.air.main.models.LockSettings2x;
	import org.bigbluebutton.air.user.models.UserRole;
	import org.bigbluebutton.air.util.ConnUtil;
	import org.bigbluebutton.air.video.commands.ShareCameraSignal;
	import org.bigbluebutton.air.video.commands.StopShareCameraSignal;
	import org.bigbluebutton.air.video.models.VideoProfile;
	import org.osflash.signals.ISignal;
	import org.osflash.signals.Signal;
	
	public class VideoConnection extends DefaultConnectionCallback implements IVideoConnection {
		private const LOG:String = "VideoConnection::";
		
		[Inject]
		public var baseConnection:IBaseConnection;
		
		[Inject]
		public var conferenceParameters:IConferenceParameters;
		
		[Inject]
		public var userSession:IUserSession;
		
		[Inject]
		public var meetingData:IMeetingData;
		
		[Inject]
		public var saveData:ISaveData;
		
		[Inject]
		public var shareCameraSignal:ShareCameraSignal;
		
		[Inject]
		public var stopShareCameraSignal:StopShareCameraSignal;
		
		private var cameraToNetStreamMap:Object = new Object();
		
		private var cameraToStreamNameMap:Object = new Object;
		
		private var _cameraPosition:String = CameraPosition.FRONT;
		
		protected var _connectionSuccessSignal:ISignal = new Signal();
		
		protected var _connectionFailureSignal:ISignal = new Signal();
		
		protected var _applicationURI:String;
		
		private var _camera:Camera;
		
		private var _selectedCameraQuality:VideoProfile;
		
		protected var _selectedCameraRotation:int;
		
		private var _connectionId : String;
		
		[PostConstruct]
		public function init():void {
			baseConnection.init(this);
			baseConnection.connectionSuccessSignal.add(onConnectionSuccess);
			baseConnection.connectionFailureSignal.add(onConnectionFailure);
			meetingData.meetingStatus.lockSettingsChangeSignal.add(lockSettingsChange);
		}
		
		private function lockSettingsChange(lockSettings:LockSettings2x):void {
			if (lockSettings.disableCam && meetingData.users.me.locked && 
				meetingData.users.me.role != UserRole.MODERATOR) {
				stopShareCameraSignal.dispatch();
			}
		}
		
		public function loadCameraSettings():void {
			if (saveData.read("cameraQuality") != null) {
				_selectedCameraQuality = userSession.videoProfileManager.getVideoProfileById(saveData.read("cameraQuality") as String);
				if (!_selectedCameraQuality) {
					_selectedCameraQuality = userSession.videoProfileManager.defaultVideoProfile;
				}
			} else {
				_selectedCameraQuality = userSession.videoProfileManager.defaultVideoProfile;
			}
			if (saveData.read("cameraRotation") != null) {
				_selectedCameraRotation = saveData.read("cameraRotation") as int;
			} else {
				_selectedCameraRotation = 0;
			}
			if (saveData.read("cameraPosition") != null) {
				_cameraPosition = saveData.read("cameraPosition") as String;
			} else {
				_cameraPosition = CameraPosition.FRONT;
			}
		}
		
		private function onConnectionFailure(reason:String):void {
			connectionFailureSignal.dispatch(reason);
		}
		
		private function onConnectionSuccess():void {
			connectionSuccessSignal.dispatch();
		}
		
		public function get connectionFailureSignal():ISignal {
			return _connectionFailureSignal;
		}
		
		public function get connectionSuccessSignal():ISignal {
			return _connectionSuccessSignal;
		}
		
		public function set uri(uri:String):void {
			_applicationURI = uri;
		}
		
		public function get uri():String {
			return _applicationURI;
		}
		
		public function get connection():NetConnection {
			return baseConnection.connection;
		}
		
		public function connect():void {
			trace("Video connect");
			_connectionId = ConnUtil.generateConnId();
			baseConnection.connect(uri, conferenceParameters.meetingID, userSession.userId, conferenceParameters.authToken, _connectionId);
		}
		
		public function disconnect(onUserCommand:Boolean):void {
			baseConnection.disconnect(onUserCommand);
		}
		
		public function get cameraPosition():String {
			return _cameraPosition;
		}
		
		public function set cameraPosition(position:String):void {
			_cameraPosition = position;
		}
		
		public function get camera():Camera {
			return _camera;
		}
		
		public function set camera(value:Camera):void {
			_camera = value;
		}
		
		public function get selectedCameraQuality():VideoProfile {
			return _selectedCameraQuality;
		}
		
		public function set selectedCameraQuality(profile:VideoProfile):void {
			_selectedCameraQuality = profile;
		}
		
		public function get selectedCameraRotation():int {
			return _selectedCameraRotation;
		}
		
		public function set selectedCameraRotation(rotation:int):void {
			_selectedCameraRotation = rotation;
		}
		
		/**
		 * Set video quality based on the user selection
		 **/
		public function selectCameraQuality(profile:VideoProfile):void {
			if (selectedCameraRotation == 90 || selectedCameraRotation == 270) {
				camera.setMode(profile.height, profile.width, profile.modeFps);
			} else {
				camera.setMode(profile.width, profile.height, profile.modeFps);
			}
			camera.setQuality(profile.qualityBandwidth, profile.qualityPicture);
			selectedCameraQuality = profile;
		}
		
		public function startPublishing(camera:Camera, streamName:String):void {
			var ns:NetStream = new NetStream(baseConnection.connection);
			cameraToStreamNameMap[camera.index] = streamName;
			cameraToNetStreamMap[camera.index] = ns;
			ns.addEventListener(NetStatusEvent.NET_STATUS, onNetStatus);
			ns.addEventListener(IOErrorEvent.IO_ERROR, onIOError);
			ns.addEventListener(AsyncErrorEvent.ASYNC_ERROR, onAsyncError);
			ns.client = this;
			ns.attachCamera(camera);
			/*
			switch (selectedCameraRotation) {
				case 90:
					streamName = "rotate_right/" + streamName;
					break;
				case 180:
					streamName = "rotate_left/rotate_left/" + streamName;
					break;
				case 270:
					streamName = "rotate_left/" + streamName;
					break;
			}
			*/
			trace(streamName);
			ns.publish(streamName);
		}
		
		private function onNetStatus(e:NetStatusEvent):void {
			trace(LOG + "onNetStatus() " + e.info.code);
		}
		
		private function onIOError(e:IOErrorEvent):void {
			trace(LOG + "onIOError() " + e.toString());
		}
		
		private function onAsyncError(e:AsyncErrorEvent):void {
			trace(LOG + "onAsyncError() " + e.toString());
		}
		
		public function getStreamNameForCamera(camera:Camera):String {
			return cameraToStreamNameMap[camera.index];
		}
		
		public function stopPublishing(camera:Camera):void {
			if (camera) {
				cameraToStreamNameMap[camera.index] = null;
				var ns:NetStream = cameraToNetStreamMap[camera.index] as NetStream;
				if (ns != null) {
					ns.attachCamera(null);
					ns.close();
					cameraToNetStreamMap[camera.index] = null;
				}
			}
		}
		
		public function stopAllPublishing():void {
			for (var key:Object in cameraToNetStreamMap) {
				var ns:NetStream = cameraToNetStreamMap[key] as NetStream;
				if (ns != null) {
					ns.attachCamera(null);
					ns.close();
					ns = null;
				}
			}
		}
	}
}
