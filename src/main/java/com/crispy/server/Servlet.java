package com.crispy.server;

import com.crispy.utils.Utils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;
import org.json.JSONObject;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;
import java.io.File;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generic Servlet class that uses reflection to dedicate tasks to respective methods.
 * This might seem performance sensitive, but Java now compiles a method into bytecode
 * after 15 or so invocations.
 *
 * Supports multipart/JSON input.
 */
public class Servlet extends HttpServlet {

    /**
     * TODO: What about delete/put ?
     */
    private MethodSpec[] getMethods;
    private MethodSpec[] postMethods;

    /**
     * Convert a string to param
     *
     * @param s
     * @param p
     * @return
     */
    static Object paramAsObject(String s, ParamType p) {
        if (s == null) return null;
        if (p == ParamType.DOUBLE) return Double.parseDouble(s);
        if (p == ParamType.LONG) return Long.parseLong(s);
        return s;
    }

    private static String getFileName(final Part part) {
        for (String content : part.getHeader("content-disposition").split(";")) {
            if (content.trim().startsWith("filename")) {
                return content.substring(content.indexOf('=') + 1).trim().replace("\"", "");
            }
        }
        return null;
    }

    private static Object getRequestParameter(HttpServletRequest req, JSONObject jsonInput, boolean isMultipart, String
            name) throws IOException, ServletException {
        if (isMultipart) {
            Part part = req.getPart(name);
            if (part == null)
                return null;
            String fileName = getFileName(part);
            if (fileName == null) {
                return IOUtils.toString(part.getInputStream());
            } else {
                File temp = File.createTempFile("tmp", "." + FilenameUtils.getExtension(fileName));
                part.write(temp.getAbsolutePath());
                return temp;
            }
        } else if (jsonInput != null) {
            return jsonInput.optString(name, null);
        } else {
            return req.getParameter(name);
        }
    }

    private MethodSpec[] methodsForAnnotation(Class<? extends Annotation> c) {
        ArrayList<MethodSpec> lMethods = new ArrayList<>();
        Method[] m = getClass().getDeclaredMethods();
        for (int i = 0; i < m.length; i++) {
            if (m[i].isAnnotationPresent(c)) {
                MethodSpec spec = new MethodSpec(m[i]);
                lMethods.add(spec);
            }
        }
        return lMethods.toArray(new MethodSpec[]{});
    }

    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);
        getMethods = methodsForAnnotation(GetMethod.class);
        postMethods = methodsForAnnotation(PostMethod.class);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        doMethod(req, resp, postMethods);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        doMethod(req, resp, getMethods);
    }

    private void doMethod(HttpServletRequest req, HttpServletResponse resp, MethodSpec[] methods) throws
            ServletException,
            IOException {
        String path = req.getPathInfo();
        String[] pathComponents = StringUtils.split(path, "/");

        JSONObject jsonInput = null;
        if ((req.getContentType() != null) && (req.getContentType().toLowerCase().indexOf("application/json") > -1)) {
            jsonInput = new JSONObject(IOUtils.toString(req.getReader()));
        }
        boolean isMultipart = ((req.getContentType() != null) && (req.getContentType().toLowerCase().indexOf("multipart/form-data") > -1));

        for (int m = 0; m < methods.length; m++) {
            MethodSpec spec = methods[m];
            if (spec.matches(pathComponents)) {
                Object[] args = new Object[spec.args.length];
                for (int a = 0; a < spec.args.length; a++) {
                    ParamType pType = spec.argTypes[a];
                    switch (pType) {
                        case REQUEST: {
                            args[a] = req;
                            break;
                        }
                        case RESPONSE: {
                            args[a] = resp;
                            break;
                        }
                        case FILE: {
                            if (!isMultipart)
                                args[a] = null;
                            else
                                args[a] = getRequestParameter(req, null, true, spec.args[a]);
                            break;
                        }
                        default: {
                            int pl = spec.argLocationInPath[a];
                            if (pl != -1) {
                                args[a] = paramAsObject(pathComponents[pl], pType);
                            } else {
                                args[a] = paramAsObject((String) getRequestParameter(req, jsonInput, isMultipart, spec.args[a])
                                        , pType);
                            }
                        }
                    }
                }

                try {
                    Object out = spec.method.invoke(this, args);
                    if (out != null) {
                        resp.getWriter().write(out.toString());
                        resp.getWriter().flush();
                    }
                } catch (Exception e) {
                    throw new ServletException(e);
                }

                return;
            }
        }
    }

    enum ParamType {
        STRING,
        LONG,
        DOUBLE,
        FILE,
        REQUEST,
        RESPONSE
    }

    class MethodSpec {
        Method method;

        String[] pathComponents;
        String[] args;
        ParamType[] argTypes;
        int[] argLocationInPath;

        MethodSpec(Method m) {
            GetMethod annt = m.getAnnotation(GetMethod.class);
            String path = annt.path();

            pathComponents = StringUtils.split(path, '/');

            int P = m.getParameterCount();

            method = m;
            args = new String[P];
            argTypes = new ParamType[P];
            argLocationInPath = new int[P];
            Parameter[] params = m.getParameters();

            for (int i = 0; i < P; i++) {
                Param annotation = params[i].getAnnotation(Param.class);
                Class type = params[i].getType();

                if (type.equals(String.class)) {
                    argTypes[i] = ParamType.STRING;
                    args[i] = annotation.value();
                    argLocationInPath[i] = ArrayUtils.indexOf(pathComponents, ":" + args[i]);
                } else if (type.equals(Long.TYPE) || type.equals(Integer.TYPE) || type.equals(Short.TYPE)) {
                    argTypes[i] = ParamType.LONG;
                    args[i] = annotation.value();
                    argLocationInPath[i] = ArrayUtils.indexOf(pathComponents, ":" + args[i]);
                } else if (type.equals(Double.TYPE) || type.equals(Float.TYPE)) {
                    argTypes[i] = ParamType.DOUBLE;
                    args[i] = annotation.value();
                    argLocationInPath[i] = -1;
                } else if (type.equals(HttpServletRequest.class)) {
                    argTypes[i] = ParamType.REQUEST;
                    args[i] = null;
                    argLocationInPath[i] = -1;
                } else if (type.equals(HttpServletResponse.class)) {
                    argTypes[i] = ParamType.RESPONSE;
                    args[i] = null;
                    argLocationInPath[i] = -1;
                }
            }
        }

        boolean matches(String[] comps) {
            if (pathComponents == null || pathComponents.length == 0) return (comps == null || comps.length == 0);

            if (pathComponents.length != comps.length)
                return false;

            for (int p = 0; p < pathComponents.length; p++) {
                if (pathComponents[p].startsWith(":")) {
                    String param = pathComponents[p].substring(1);
                    int paramIndex = ArrayUtils.indexOf(args, param);
                    if (paramIndex == -1)
                        return false;
                    ParamType pType = argTypes[paramIndex];
                    if (pType == ParamType.LONG && !StringUtils.isNumeric(comps[p])) {
                        return false;
                    }
                    if (pType == ParamType.STRING && StringUtils.isNumeric(comps[p])) {
                        return false;
                    }
                } else {
                    if (!pathComponents[p].equals(comps[p])) {
                        return false;
                    }
                }
            }
            return true;
        }
    }
}
