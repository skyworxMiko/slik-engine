package com.ilkeiapps.slik.slikengine.bean;

import lombok.Data;

import java.util.List;

@Data
public class ActuatorResponse {
	private String baseUnit;
	private List<AvailableTagsItem> availableTags;
	private String name;
	private String description;
	private List<MeasurementsItem> measurements;
}
