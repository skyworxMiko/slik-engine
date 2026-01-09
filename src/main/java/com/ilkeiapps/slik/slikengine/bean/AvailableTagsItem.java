package com.ilkeiapps.slik.slikengine.bean;

import lombok.Data;

import java.util.List;

@Data
public class AvailableTagsItem{
	private List<String> values;
	private String tag;
}
