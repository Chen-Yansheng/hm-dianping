package com.chen.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result {
    private Boolean success;    //状态码, true成功, false失败
    private String msg;    //状态码信息
    private Object data;    //响应给前端的数据

    public static Result success(){
        return new Result(true, "success", null);
    }

    public static Result success(Object data){
        return new Result(true, "success", data);
    }

    public static Result fail(String msg){
        return new Result(false, msg, null);
    }
}
