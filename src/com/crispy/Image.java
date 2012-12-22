package com.crispy;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.IOUtils;
import org.json.JSONObject;

@WebServlet(urlPatterns = { "/resource", "/resource/*" })
public class Image extends HttpServlet {

	private static AtomicLong mID = new AtomicLong(System.currentTimeMillis());

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp)
			throws ServletException, IOException {
		String fileName = req.getPathInfo();
		if (fileName.startsWith("/class")) {
			doClass(fileName.substring(fileName.indexOf('/', 1)), resp);
		} else if (fileName.startsWith("/local")) {
			doLocal(fileName.substring(fileName.indexOf('/', 1)), resp);
		}
	}
	
	

	private void doClass(String path, HttpServletResponse resp) throws IOException {
		IOUtils.copy(getClass().getResourceAsStream(path),
				resp.getOutputStream());
		resp.getOutputStream().flush();
	}

	private void doLocal(String path, HttpServletResponse resp) throws IOException {
		String extension = path.substring(path.lastIndexOf('.') + 1);
		if (extension.equals("jpg")) {
			extension = "jpeg";
		}
		File realFile = new File(path);
		if (!realFile.exists()) {
			resp.setStatus(402);
			return;
		}
		resp.setContentType("image/" + extension);
		IOUtils.copy(new FileInputStream(realFile), resp.getOutputStream());
		resp.getOutputStream().flush();
	}



	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse resp)
			throws ServletException, IOException {
		try {
			long nextID = mID.incrementAndGet();
			String sourceFileName = req.getHeader("X-File-Name");
			String uploadFolder = req.getHeader("X-Upload-Folder");
			String ext = sourceFileName.substring(sourceFileName
					.lastIndexOf('.') + 1);
			File f = new File(uploadFolder, nextID + "." + ext);
			IOUtils.copy(req.getInputStream(), new FileOutputStream(f));
			resp.getWriter().write(
					new JSONObject().put("success", true)
							.put("value", f.getAbsolutePath()).toString());
			resp.getWriter().flush();
		} catch (Exception e) {
			e.printStackTrace();
			resp.setStatus(resp.SC_INTERNAL_SERVER_ERROR);
			resp.getWriter().print("{success: false}");
			resp.getWriter().flush();
		}
	}
}
