package com.ejlchina.http;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.concurrent.Executor;

import com.ejlchina.http.internal.HttpException;

import okhttp3.internal.Util;

/**
 * 文件下载
 * @author Troy.Zhou
 * @since 2.2.0
 */
public class Download {
	
	static final int DEFAULT_SIZE = 8192;
	
	private File file;
	private InputStream input;
	private OnCallback<File> onSuccess;
	private OnCallback<Failure> onFailure;
	private Executor callbackExecutor;
	private long doneBytes;
	private int buffSize = 0;
	private long seekBytes = 0;
	private boolean breakpointResumed;
	private volatile int status;
	private Object lock = new Object();
	
	public Download(File file, InputStream input, Executor executor, long skipBytes) {
		this.file = file;
		this.input = input;
		this.callbackExecutor = executor;
		this.seekBytes = skipBytes;
	}

	/**
	 * 设置缓冲区大小，默认 2K（2048）
	 * @param buffSize 缓冲区大小（单位：字节）
	 * @return
	 */
	public Download setBuffSize(int buffSize) {
		if (buffSize > 0) {
			this.buffSize = buffSize;
		}
		return this;
	}
	
	/**
	 * 启用断点续传
	 * @return Download
	 */
	public Download resumeBreakpoint() {
		this.breakpointResumed = true;
		return this;
	}
	
	/**
	 * 设置文件指针，从文件的 seekBytes 位置追加内容
	 * @return Download
	 */
	public Download setFilePointer(long seekBytes) {
		this.seekBytes = seekBytes;
		return this;
	}
	
	/**
	 * 设置下载成功回调
	 * @param onSuccess 成功回调函数
	 * @return Download
	 */
	public Download setOnSuccess(OnCallback<File> onSuccess) {
		this.onSuccess = onSuccess;
		return this;
	}
	
	/**
	 * 设置下载失败回调
	 * @param onFailure 失败回调函数
	 * @return Download
	 */
	public Download setOnFailure(OnCallback<Failure> onFailure) {
		this.onFailure = onFailure;
		return this;
	}
	
	/**
	 * 开始下载
	 * @return 下载控制器
	 */
	public Ctrl start() {
		status = Ctrl.STATUS__DOWNLOADING;
		if (buffSize == 0) {
			buffSize = DEFAULT_SIZE;
		}
		RandomAccessFile raFile = randomAccessFile();
		new Thread(() -> {
			doDownload(raFile);
		}).start();
		return new Ctrl();
	}
	
	public class Ctrl {
		
		/**
		 * 已取消
		 */
		public static final int STATUS__CANCELED = -1;
		
		/**
		 * 下载中
		 */
		public static final int STATUS__DOWNLOADING = 1;
		
		/**
		 * 已暂停
		 */
		public static final int STATUS__PAUSED = 2;
		
		/**
		 * 已完成
		 */
		public static final int STATUS__DONE = 3;
		
		/**
		 * 错误
		 */
		public static final int STATUS__ERROR = 4;
		
		/**
		 * @set {@link #STATUS__CANCELED}
		 * @set {@link #STATUS__DOWNLOADING}
		 * @set {@link #STATUS__PAUSED}
		 * @set {@link #STATUS__DONE}
		 * @return 下载状态
		 */
		public int status() {
			return status;
		}
		
		/**
		 * 暂停下载任务
		 */
		public void pause() {
			synchronized (lock) {
				if (status == STATUS__DOWNLOADING) {
					status = STATUS__PAUSED;
				}
			}
		}
		
		/**
		 * 继续下载任务
		 */
		public void resume() {
			synchronized (lock) {
				if (status == STATUS__PAUSED) {
					status = STATUS__DOWNLOADING;
				}
			}
		}
		
		/**
		 * 取消下载任务
		 */
		public void cancel() {
			synchronized (lock) {
				if (status == STATUS__PAUSED || status == STATUS__DOWNLOADING) {
					status = STATUS__CANCELED;
				}
			}
		}
		
	}
	
	public class Failure {

		private IOException exception;

		Failure(IOException exception) {
			this.exception = exception;
		}
		
		/**
		 * @return 下载文件
		 */
		public File getFile() {
			return file;
		}
		
		/**
		 * @return 已下载字节数
		 */
		public long getDoneBytes() {
			return doneBytes;
		}

		/**
		 * @return 异常信息
		 */
		public IOException getException() {
			return exception;
		}
		
	}
	
	private RandomAccessFile randomAccessFile() {
		try {
			return new RandomAccessFile(file, "rw");
		} catch (FileNotFoundException e) {
			status = Ctrl.STATUS__ERROR;
			Util.closeQuietly(input);
			throw new HttpException("无法获取文件[" + file.getAbsolutePath() + "]的输入流", e);
		}
	}
	
	private void doDownload(RandomAccessFile raFile) {
		try {
			if (breakpointResumed && seekBytes > 0) {
				long length = raFile.length();
				if (seekBytes <= length) {
					raFile.seek(seekBytes);
					doneBytes = seekBytes;
				} else {
					raFile.seek(length);
					doneBytes = length;
				}
			}
			while (status != Ctrl.STATUS__CANCELED && status != Ctrl.STATUS__DONE) {
				if (status == Ctrl.STATUS__DOWNLOADING) {
					byte[] buff = new byte[buffSize];
					int len = -1;
					while ((len = input.read(buff)) != -1) {
						raFile.write(buff, 0, len);
						doneBytes += len;
						if (status == Ctrl.STATUS__CANCELED 
								|| status == Ctrl.STATUS__PAUSED) {
							break;
						}
					}
					if (len == -1) {
						synchronized (lock) {
							status = Ctrl.STATUS__DONE;
						}
					}
				}
			}
		} catch (IOException e) {
			synchronized (lock) {
				status = Ctrl.STATUS__ERROR;
			}
			if (onFailure != null) {
				callbackExecutor.execute(() -> {
					onFailure.on(new Failure(e));
				});
			} else {
				throw new HttpException("流传输失败", e);
			}
		} finally {
			Util.closeQuietly(raFile);
			Util.closeQuietly(input);
			if (status == Ctrl.STATUS__CANCELED) {
				file.delete();
			}
		}
		if (status == Ctrl.STATUS__DONE 
				&& onSuccess != null) {
			callbackExecutor.execute(() -> {
				onSuccess.on(file);
			});
		}
	}

}
