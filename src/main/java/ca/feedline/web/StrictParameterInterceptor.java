package ca.feedline.web;

import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.HashSet;
import java.util.Set;

public class StrictParameterInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (handler instanceof HandlerMethod hm) {
            Set<String> allowed = new HashSet<>();
            for (org.springframework.core.MethodParameter mp : hm.getMethodParameters()) {
                org.springframework.web.bind.annotation.RequestParam rp =
                        mp.getParameterAnnotation(org.springframework.web.bind.annotation.RequestParam.class);
                if (rp != null) {
                    String name = rp.name();
                    if (name == null || name.isEmpty()) {
                        name = rp.value();
                    }
                    if (name == null || name.isEmpty()) {
                        name = mp.getParameterName();
                    }
                    if (name != null) {
                        allowed.add(name);
                    }
                }
            }
            java.util.Enumeration<String> names = request.getParameterNames();
            while (names.hasMoreElements()) {
                String p = names.nextElement();
                if (!allowed.contains(p)) {
                    throw new UnknownQueryParameterException(p);
                }
            }
        }
        return true;
    }
}
