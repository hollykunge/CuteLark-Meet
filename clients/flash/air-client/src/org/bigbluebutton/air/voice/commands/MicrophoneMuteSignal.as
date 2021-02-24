package org.bigbluebutton.air.voice.commands {
	
	import org.osflash.signals.Signal;
	
	public class MicrophoneMuteSignal extends Signal {
		public function MicrophoneMuteSignal() {
			/**
			 * @1 microphone enabled
			 */
			super(String);
		}
	}
}
