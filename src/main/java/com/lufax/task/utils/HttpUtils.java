package com.lufax.task.utils;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.io.StreamUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.tasks.Task;
import com.intellij.tasks.TaskRepository;
import com.intellij.tasks.config.TaskSettings;
import com.intellij.tasks.generic.GenericRepository;
import com.intellij.tasks.generic.GenericRepositoryUtil;
import com.intellij.tasks.generic.TemplateVariable;
import com.intellij.tasks.impl.BaseRepository;
import com.intellij.tasks.impl.BaseRepositoryImpl;
import com.intellij.tasks.impl.httpclient.NewBaseRepositoryImpl;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.net.HTTPMethod;
import com.intellij.util.net.IdeHttpClientHelpers;
import com.intellij.util.net.ssl.CertificateManager;
import com.lufax.task.SuperGenericRepository;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.httpclient.*;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class HttpUtils {

    private static final Logger LOG = Logger.getInstance(SuperGenericRepository.class);

    public static String executeMethod(TaskRepository taskRepository, HTTPMethod requestType, String requestUrl) throws Exception {
        return executeMethod(taskRepository, requestType, requestUrl, null);

    }
    public static String executeMethod(TaskRepository taskRepository, HTTPMethod requestType, String requestUrl, List<TemplateVariable> requestTemplateVariables) throws Exception {
        if (CollectionUtils.isNotEmpty(requestTemplateVariables)) {
            requestUrl = GenericRepositoryUtil.substituteTemplateVariables(requestUrl, requestTemplateVariables);
        }

        if (taskRepository instanceof BaseRepositoryImpl) {
            return b_executeMethod(taskRepository, getHttpMethod((BaseRepositoryImpl) taskRepository, requestUrl, requestType, requestTemplateVariables));
        } else if (taskRepository instanceof NewBaseRepositoryImpl) {
            throw new RuntimeException("Unsupported Repository Type:" + taskRepository.getClass());
        } else {
            throw new RuntimeException("Unsupported Repository Type:" + taskRepository.getClass());
        }
    }

    public static HttpMethod getHttpMethod(BaseRepositoryImpl taskRepository, String requestUrl, HTTPMethod type, List<TemplateVariable> requestTemplateVariables) {
        HttpMethod method;
        try {
            if (type == HTTPMethod.GET) {
                method = new GetMethod(GenericRepositoryUtil.substituteTemplateVariables(requestUrl, requestTemplateVariables));
            } else {
                int n = requestUrl.indexOf('?');
                String url = n == -1 ? requestUrl : requestUrl.substring(0, n);
                method = new PostMethod(GenericRepositoryUtil.substituteTemplateVariables(url, requestTemplateVariables));
                String[] queryParams = requestUrl.substring(n + 1).split("&");
                ((PostMethod) method).addParameters(ContainerUtil.map2Array(queryParams, NameValuePair.class, s -> {
                    String[] nv = s.split("=");
                    try {
                        if (nv.length == 1) {
                            return new NameValuePair(GenericRepositoryUtil.substituteTemplateVariables(nv[0], requestTemplateVariables, false), "");
                        }
                        return new NameValuePair(GenericRepositoryUtil.substituteTemplateVariables(nv[0], requestTemplateVariables, false), GenericRepositoryUtil.substituteTemplateVariables(nv[1], requestTemplateVariables, false));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }));
            }
            Method reflectMethod = BaseRepositoryImpl.class.getDeclaredMethod("configureHttpMethod", HttpMethod.class);
            reflectMethod.setAccessible(true);
            reflectMethod.invoke(taskRepository, method);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return method;
    }

    public static String addSchemeIfNoneSpecified(TaskRepository taskRepository, @Nullable String url) {
        if (StringUtil.isNotEmpty(url)) {
            try {
                final String scheme = new URI(url).getScheme();
                // For URL like "foo.bar:8080" host name will be parsed as scheme
                if (scheme == null) {
                    if (taskRepository instanceof BaseRepository) {
                        url = getDefaultScheme((BaseRepository) taskRepository) + "://" + url;
                    } else {
                        url = "http://" + url;
                    }
                }
            }
            catch (URISyntaxException ignored) {
            }
        }
        return url;
    }

    private static String b_executeMethod(TaskRepository taskRepository, HttpMethod method) throws Exception {
        String responseBody;
        Method reflectMethod = BaseRepositoryImpl.class.getDeclaredMethod("getHttpClient");
        reflectMethod.setAccessible(true);
        HttpClient httpClient = (HttpClient) reflectMethod.invoke(taskRepository);
        httpClient.executeMethod(method);
        Header contentType = method.getResponseHeader("Content-Type");
        if (contentType != null && contentType.getValue().contains("charset")) {
            // ISO-8859-1 if charset wasn't specified in response
            responseBody = StringUtil.notNullize(method.getResponseBodyAsString());
        }
        else {
            InputStream stream = method.getResponseBodyAsStream();
            if (stream == null) {
                responseBody = "";
            }
            else {
                try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                    responseBody = StreamUtil.readText(reader);
                }
            }
        }
        LOG.info(responseBody);
        if (method.getStatusCode() != HttpStatus.SC_OK) {
            throw new Exception("Request failed with HTTP error: " + method.getStatusText());
        }
        return responseBody;
    }

    private static String getDefaultScheme(BaseRepository taskRepository) {
        try {
            Method reflectMethod = BaseRepository.class.getDeclaredMethod("getDefaultScheme");
            reflectMethod.setAccessible(true);
            return (String) reflectMethod.invoke(taskRepository);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

}
