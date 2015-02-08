package com.thoughtworks.go.server;

import com.thoughtworks.go.util.SystemEnvironment;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.webapp.WebAppContext;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

public class AssetsContextHandler extends ContextHandler {
    private final AssetsHandler handler;
    private SystemEnvironment systemEnvironment;

    public AssetsContextHandler(SystemEnvironment systemEnvironment) throws IOException {
        super(systemEnvironment.getWebappContextPath() + "/assets");
        this.systemEnvironment = systemEnvironment;
        handler = new AssetsHandler();
        setHandler(handler);
    }

    public void init(WebAppContext webAppContext) throws IOException {
        String railsRootDirName = webAppContext.getInitParameter("rails.root").replaceAll("/WEB-INF/", "");
        String assetsDir = webAppContext.getWebInf().addPath(String.format("%s/public/assets/", railsRootDirName)).getName();
        handler.setAssetsDir(assetsDir);
    }

    public void handle(String target, javax.servlet.http.HttpServletRequest request, javax.servlet.http.HttpServletResponse response, int dispatch) throws IOException, javax.servlet.ServletException {
        if (shouldNotHandle()) return;
        Request base_request = (request instanceof Request) ? (Request) request : null;
        if (target.startsWith(this.getContextPath()) && !base_request.isHandled()) {
            superDotHandle(target, base_request, request, response);
        }
    }

    void superDotHandle(String target, Request base_request, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        super.handle(target, base_request, request, response);
    }

    private boolean shouldNotHandle() {
        return !systemEnvironment.useCompressedJs();
    }

    private class AssetsHandler extends AbstractHandler
    {
        private ResourceHandler resourceHandler = new ResourceHandler();

        private AssetsHandler() {
            resourceHandler.setCacheControl("max-age=31536000,public");
        }

        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            if (shouldNotHandle()) return;
            this.resourceHandler.handle(target, baseRequest, request, response);
        }

        private void setAssetsDir(String assetsDir) {
            resourceHandler.setResourceBase(assetsDir);
        }
    }

}
