package dev.hexpath.geo.api;

import dev.hexpath.geo.dataset.DatasetSnapshot;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Lets controllers declare a DatasetSnapshot parameter and receive the one pinned by
 * RequestContextFilter. Controllers never read SnapshotHolder directly.
 */
public class PinnedSnapshotArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == DatasetSnapshot.class;
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav, NativeWebRequest request,
                                  WebDataBinderFactory binderFactory) {
        Object s = request.getAttribute(RequestContextFilter.SNAPSHOT_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (s == null) {
            throw new ApiProblemException(ProblemType.SERVICE_NOT_READY, "no dataset release loaded yet");
        }
        return s;
    }
}
