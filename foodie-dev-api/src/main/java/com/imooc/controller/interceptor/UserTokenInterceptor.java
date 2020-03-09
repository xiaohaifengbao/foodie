package com.imooc.controller.interceptor;

import com.imooc.utils.IMOOCJSONResult;
import com.imooc.utils.JsonUtils;
import com.imooc.utils.RedisOperator;
import io.netty.util.internal.StringUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.Nullable;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;

@Configuration
public class UserTokenInterceptor implements HandlerInterceptor {

    // 用户ID
    public static final String HEADER_USER_ID = "headerUserId";
    //用户token
    public static final String HEADER_USER_TOKEN = "headerUserToken";
    // token-key值
    public static final String REDIS_USER_TOKEN = "redis_user_token";
    @Autowired
    private RedisOperator redisOperator;
    /**
     * 接口请求之前拦截
     * @param request
     * @param response
     * @param handler
     * @return
     * @throws Exception
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String headerUserId = request.getHeader(HEADER_USER_ID);
        String headerUserToken = request.getHeader(HEADER_USER_TOKEN);

        if(StringUtils.isBlank(headerUserId) && StringUtils.isBlank(headerUserToken)) {
            returnResultMsg(response, IMOOCJSONResult.errorMsg("请登录。。。"));
            return false;
        }else {
            String resultToken = redisOperator.get(REDIS_USER_TOKEN + ":" + headerUserId);
            if(StringUtils.isBlank(resultToken)) {
                returnResultMsg(response, IMOOCJSONResult.errorMsg("请登录。。。"));
                return false;
            }else {
                if(!resultToken.equals(headerUserToken)) {
                    returnResultMsg(response, IMOOCJSONResult.errorMsg("异地登录,请重新登录"));
                    return false;
                }
            }
        }

        return true;
    }

    private void returnResultMsg(HttpServletResponse response, IMOOCJSONResult result) {
        response.setCharacterEncoding("UTF-8");
        response.setContentType("text/json");
        OutputStream os = null;
        try {
            os = response.getOutputStream();
            os.write(JsonUtils.objectToJson(result).getBytes("UTF-8"));
        } catch (IOException e) {
            e.printStackTrace();
        }finally {
            if(os != null) {
                try {
                    os.close();
                    os.flush();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * 接口请求之后，页面渲染之前
     * @param request
     * @param response
     * @param handler
     * @param modelAndView
     * @throws Exception
     */
    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView) throws Exception {

    }

    /**
     * 接口请求之后，页面渲染之后
     * @param request
     * @param response
     * @param handler
     * @param ex
     * @throws Exception
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {

    }
}
