package com.ilkeiapps.slik.slikengine.bean;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;

@Setter
@Getter
@ToString(includeFieldNames=true)
public class ApiResponse<T>
{
    private Boolean status;
    private String message;
    private Integer maxPage;
    private Integer perPage;
    private List<T> data;


    public void insertNewData(T data)
    {
        this.data = new ArrayList<T>();
        this.data.add(data);
    }

    public void addData(T data)
    {
        if (this.data == null) {
            this.data = new ArrayList<T>();
        }
        this.data.add(data);
    }
}
