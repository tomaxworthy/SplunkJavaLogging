package com.splunk.logging;

import com.google.gson.annotations.SerializedName;

public class HECTransportMessage {

	private String event;
	private String index;
	private String source;
	@SerializedName("sourcetype")
	private String sourceType;
	
	public HECTransportMessage() {}
	
	public HECTransportMessage(String event, String index, String source, String sourceType){
		setEvent(event);
		setIndex(index);
		setSource(source);
		setSourceType(sourceType);
	}
	public String getEvent() {
		return event;
	}
	public void setEvent(String event) {
		this.event = event;
	}
	public String getIndex() {
		return index;
	}
	public void setIndex(String index) {
		this.index = index;
	}
	public String getSourceType() {
		return sourceType;
	}
	public void setSourceType(String sourceType) {
		this.sourceType = sourceType;
	}
	public String getSource() {
		return source;
	}
	public void setSource(String source) {
		this.source = source;
	}


}
