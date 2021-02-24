package org.bigbluebutton.air.whiteboard.models {
	import org.bigbluebutton.air.whiteboard.views.WhiteboardCanvas;
	
	import spark.components.Group;
	
	public class Annotation {
		private var _id:String = "undefined";
		
		private var _userId:String = "undefined";
		
		protected var _status:String = AnnotationStatus.DRAW_START;
		
		private var _type:String = "undefined";
		
		protected var _annInfo:Object;
		
		protected var _parentWidth:Number = 0;
		
		protected var _parentHeight:Number = 0;
		
		public function Annotation(id:String, userId:String, type:String, status:String, annInfo:Object) {
			_id = id;
			_userId = userId;
			_type = type;
			_status = status;
			_annInfo = annInfo;
		}
		
		public function get id():String {
			return _id;
		}
		
		public function get userId():String {
			return _userId;
		}
		
		public function get type():String {
			return _type;
		}
		
		public function get status():String {
			return _status;
		}
		
		public function get annInfo():Object {
			return _annInfo;
		}
		
		protected final function denormalize(val:Number, side:Number):Number {
			return (val * side) / 100.0;
		}
		
		protected final function normalize(val:Number, side:Number):Number {
			return (val * 100.0) / side;
		}
		
		protected function makeGraphic():void {
		}
		
		public function update(an:Annotation):void {
			if (an.id == this.id) {
				_status = an.status;
				_annInfo = an.annInfo;
				
				if (shouldDraw()) {
					makeGraphic();
				}
			}
		}
		
		public function draw(canvas:Group):void {
			var width:Number = canvas.width;
			var height:Number = canvas.height;
			
			if (_parentWidth != width || _parentHeight != height) {
				_parentWidth = width;
				_parentHeight = height;
				if (shouldDraw()) {
					makeGraphic();
				}
			}
		}
		
		public function remove(canvas:Group):void {
			_parentWidth = 0;
			_parentHeight = 0;
		}
		
		protected function shouldDraw():Boolean {
			return _parentHeight > 0 && _parentWidth > 0;
		}
	}
}
