package com.waqas.hybrid;

import java.io.IOException;

import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.annotation.SuppressLint;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.Camera;
import android.hardware.Camera.CameraInfo;
import android.hardware.Camera.Parameters;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.support.v4.app.FragmentActivity;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.RelativeLayout.LayoutParams;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.CameraBridgeViewBase;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewFrame;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewListener2;
//import org.opencv.android.FpsMeter;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.JavaCameraView;
import org.opencv.android.NativeCameraView;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Mat;

import com.nummist.secondsight.R;
import com.waqas.hybrid.adapters.CameraProjectionAdapter;
import com.waqas.hybrid.ar.ARFilter;
import com.waqas.hybrid.ar.ARProcessing;
import com.waqas.hybrid.ar.NoneARFilter;

public final class CameraActivity extends FragmentActivity implements
		CvCameraViewListener2, SensorEventListener {

	// A tag for log output.
	private static final String TAG = "CameraActivity";

	// final FpsMeter fpsCounter = new FpsMeter();

	// A key for storing the index of the active camera.
	private static final String STATE_CAMERA_INDEX = "cameraIndex";

	// Keys for storing the indices of the active filters.
	private static final String STATE_IMAGE_DETECTION_FILTER_INDEX = "imageDetectionFilterIndex";

	// ////////////////////////////////////////////////////////////////////////////
	private long startTime = 0L;
	private Handler myHandler = new Handler();
	long timeInMillies = 0L;
	long timeSwap = 0L;
	long finalTime = 0L;

	// Sensor Addition
	ToggleButton bCalibTB;
	Button bCalib, bIncCount, bDecCount;
	TextView tvX, tvY, tvZ, tvCount, tvTimer;

	private SensorManager sensorManager = null;
	private float x, y, z;

	// deltas for calibration
	private float cx, cy, cz;

	private long lastUpdate = -1;

	// ////////////////////////////////////////////////////////////////////////////////

	// counter
	int counter = 0;
	int count = 1;
	int totalFrames = 0;

	// The filters.
	private ARFilter[] mImageDetectionFilters;

	// The indices of the active filters.
	private int mImageDetectionFilterIndex;

	// The index of the active camera.
	private int mCameraIndex;

	// Whether the active camera is front-facing.
	// If so, the camera view should be mirrored.
	private boolean mIsCameraFrontFacing;

	// The number of cameras on the device.
	private int mNumCameras;

	// The camera view.
	private CameraBridgeViewBase mOpenCvCameraView;

	// An adapter between the video camera and projection matrix.
	private CameraProjectionAdapter mCameraProjectionAdapter;

	// The renderer for 3D augmentations.
	private ARCubeRenderer mARRenderer;

	// Whether the next camera frame should be saved as a photo.
	private boolean mIsPhotoPending;

	// A matrix that is used when saving photos.
	private Mat mBgr;

	// Whether an asynchronous menu action is in progress.
	// If so, menu interaction should be disabled.
	private boolean mIsMenuLocked;
	
	
	
	// The OpenCV loader callback.
	private BaseLoaderCallback mLoaderCallback = new BaseLoaderCallback(this) {
		@Override
		public void onManagerConnected(final int status) {
			switch (status) {
			case LoaderCallbackInterface.SUCCESS:
				Log.d(TAG, "OpenCV loaded successfully");
				mOpenCvCameraView.enableView();
				mBgr = new Mat();

				final ARFilter starryNight;
				try {
					starryNight = new ARProcessing(CameraActivity.this,
							R.drawable.boat, mCameraProjectionAdapter);

				} catch (IOException e) {
					Log.e(TAG, "Failed to load drawable: " + "starry_night");
					e.printStackTrace();
					break;
				}

				final ARFilter akbarHunting;
				try {
					akbarHunting = new ARProcessing(CameraActivity.this,
							R.drawable.graffiti, mCameraProjectionAdapter);
				} catch (IOException e) {
					Log.e(TAG, "Failed to load drawable: "
							+ "akbar_hunting_with_cheetahs");
					e.printStackTrace();
					break;
				}

				mImageDetectionFilters = new ARFilter[] { new NoneARFilter(),
						starryNight, akbarHunting };

				break;
			default:
				super.onManagerConnected(status);
				break;
			}
		}
	};

	@SuppressLint("NewApi")
	@Override
	protected void onCreate(final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		final Window window = getWindow();
		window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

		if (savedInstanceState != null) {
			mCameraIndex = savedInstanceState.getInt(STATE_CAMERA_INDEX, 0);
			mImageDetectionFilterIndex = savedInstanceState.getInt(
					STATE_IMAGE_DETECTION_FILTER_INDEX, 0);
		} else {
			mCameraIndex = 0;
			mImageDetectionFilterIndex = 0;
		}

		FrameLayout layout = new FrameLayout(this);
		layout.setLayoutParams(new FrameLayout.LayoutParams(
				FrameLayout.LayoutParams.MATCH_PARENT,
				FrameLayout.LayoutParams.MATCH_PARENT));
		setContentView(layout);

		mOpenCvCameraView = new JavaCameraView(this, mCameraIndex);
		mOpenCvCameraView.setCvCameraViewListener(this);

		// setting the maximum resolution
		mOpenCvCameraView.setMaxFrameSize(640, 480);

		mOpenCvCameraView.enableFpsMeter();

		mOpenCvCameraView.setLayoutParams(new FrameLayout.LayoutParams(
				FrameLayout.LayoutParams.MATCH_PARENT,
				FrameLayout.LayoutParams.MATCH_PARENT));
		layout.addView(mOpenCvCameraView);

		GLSurfaceView glSurfaceView = new GLSurfaceView(this);
		glSurfaceView.getHolder().setFormat(PixelFormat.TRANSPARENT);
		glSurfaceView.setEGLConfigChooser(8, 8, 8, 8, 0, 0);
		glSurfaceView.setZOrderOnTop(true);
		glSurfaceView.setLayoutParams(new FrameLayout.LayoutParams(
				FrameLayout.LayoutParams.MATCH_PARENT,
				FrameLayout.LayoutParams.MATCH_PARENT));
		layout.addView(glSurfaceView);

		// //////////////////////////////////////////////////////////////////////////////////////////////
		startTime = SystemClock.uptimeMillis();
		myHandler.postDelayed(updateTimerMethod, 0);
		// Get a reference to a SensorManager
		sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);

		bCalibTB = new ToggleButton(this);
		bCalib = new Button(this);
		bIncCount = new Button(this);
		bDecCount = new Button(this);
		tvX = new TextView(this);
		tvY = new TextView(this);
		tvZ = new TextView(this);
		tvCount = new TextView(this);
		tvTimer = new TextView(this);

		LinearLayout.LayoutParams buttonLayout = new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.WRAP_CONTENT,
				LinearLayout.LayoutParams.WRAP_CONTENT);
		buttonLayout.setMargins(300, 200, 300, 0);

		RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(
				LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
		lp.addRule(RelativeLayout.ALIGN_TOP);
		lp.leftMargin = 80;
		lp.topMargin = 80;

		bCalibTB.setPadding(30, 30, 30, 30);
		bCalibTB.setMaxHeight(100);
		bCalibTB.setX(600);//(200);
		bCalibTB.setY(30);
		bCalibTB.setTextOff("Visual Tracking");
		bCalibTB.setTextOn("Hybrid Tracking");
		bCalibTB.setLayoutParams(buttonLayout);
		bCalibTB.setChecked(false);
		bCalibTB.setOnClickListener(new View.OnClickListener() {

			@Override
			public void onClick(View view) {

				boolean on = ((ToggleButton) view).isChecked();

				if (on) {

					sensorManager
							.registerListener(
									CameraActivity.this,
									sensorManager
											.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
									SensorManager.SENSOR_DELAY_NORMAL);
					bIncCount.setVisibility(view.GONE);
					bDecCount.setVisibility(view.GONE);
					Toast.makeText(getApplicationContext(), "Hybrid Tracking",
							Toast.LENGTH_SHORT).show();

					// Enable vibrate
				} else {
					sensorManager.unregisterListener(CameraActivity.this);
					sensorManager = null;

					cx = 0;
					cy = 0;
					cz = 0;

					Toast.makeText(getApplicationContext(), "Visual Tracking",
							Toast.LENGTH_SHORT).show();

				}
			}
		});

		bCalib.setPadding(30, 30, 30, 30);

		bCalib.setX(1000);//(600);
		bCalib.setY(30);
		bCalib.setLayoutParams(buttonLayout);
		bCalib.setText("Callibrate");
		bCalib.setTextColor(Color.BLACK);
		// bCalib.setBackgroundColor(0);
		bCalib.setOnClickListener(new View.OnClickListener() {

			@Override
			public void onClick(View v) {
				bIncCount.setVisibility(v.GONE);
				bDecCount.setVisibility(v.GONE);
				cx = -x;
				cy = -y;
				cz = -z;

			}
		});

		bIncCount.setPadding(30, 30, 30, 30);

		bIncCount.setX(600);
		bIncCount.setY(200);
		bIncCount.setLayoutParams(buttonLayout);
		bIncCount.setText("Increment");
		bIncCount.setTextColor(Color.BLACK);
		
		// bCalib.setBackgroundColor(0);
		bIncCount.setOnClickListener(new View.OnClickListener() {

			@Override
			public void onClick(View v) {
				/*
				 * int i; for (i = 0; i < 10; i++) { count = i;
				 * tvCount.setText("Count " + count + "  |   Counter " +
				 * counter); } i = 0;
				 */
				count++;
				tvCount.setText("Count " + count + "  |   Counter " + counter);
			}
		});

		bDecCount.setPadding(30, 30, 30, 30);

		bDecCount.setX(1000);
		bDecCount.setY(200);
		bDecCount.setLayoutParams(buttonLayout);
		bDecCount.setText("Decrement");
		bDecCount.setTextColor(Color.BLACK);
		// bCalib.setBackgroundColor(0);
		bDecCount.setOnClickListener(new View.OnClickListener() {

			@Override
			public void onClick(View v) {

				count--;
				tvCount.setText("Count " + count + "  |   Counter " + counter);
			}
		});

		tvX.setX(0);// .setPadding(0, 120, 100, 0);
		tvX.setY(180);
		tvX.setLayoutParams(lp);
		tvX.setText("X: ");
		tvX.setTextSize(18);
		tvX.setTextColor(Color.BLUE);
		tvX.setBackgroundColor(Color.WHITE);

		tvY.setX(0);// setPadding(0, 150, 100, 0);
		tvY.setY(250);
		tvY.setLayoutParams(lp);
		tvY.setText("Y: ");
		tvY.setTextSize(18);
		tvY.setTextColor(Color.BLUE);
		tvY.setBackgroundColor(Color.WHITE);

		tvZ.setX(0);
		tvZ.setY(320);
		tvZ.setLayoutParams(lp);
		tvZ.setText("Z: ");
		tvZ.setTextSize(18);
		tvZ.setTextColor(Color.BLUE);
		tvZ.setBackgroundColor(Color.WHITE);

		tvCount.setX(0);
		tvCount.setY(450);// setPadding(0, 210, 100, 0);
		tvCount.setLayoutParams(lp);
		tvCount.setText("Counter Value: " + count);
		tvCount.setTextColor(Color.BLUE);
		tvCount.setTextSize(15);
		tvCount.setBackgroundColor(Color.CYAN);

		tvTimer.setX(0);
		tvTimer.setY(650);
		// tvTimer.setPadding(0, 230, 100, 0);
		tvTimer.setLayoutParams(lp);
		tvTimer.setText("Counter Value: ");
		tvTimer.setTextColor(Color.BLUE);
		tvTimer.setTextSize(20);
		tvTimer.setBackgroundColor(Color.YELLOW);

		layout.addView(bCalibTB);
		layout.addView(bCalib);
		layout.addView(bIncCount);
		layout.addView(bDecCount);
		layout.addView(tvX);
		layout.addView(tvY);
		layout.addView(tvZ);
		layout.addView(tvCount);
		layout.addView(tvTimer);

		// //////////////////////////////////////////////////////////////////////////////////////////

		mCameraProjectionAdapter = new CameraProjectionAdapter();

		mARRenderer = new ARCubeRenderer();
		mARRenderer.cameraProjectionAdapter = mCameraProjectionAdapter;
		glSurfaceView.setRenderer(mARRenderer);

		final Camera camera;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD) {
			CameraInfo cameraInfo = new CameraInfo();
			Camera.getCameraInfo(mCameraIndex, cameraInfo);
			mIsCameraFrontFacing = (cameraInfo.facing == CameraInfo.CAMERA_FACING_FRONT);
			mNumCameras = Camera.getNumberOfCameras();
			camera = Camera.open(mCameraIndex);
		} else { // pre-Gingerbread
			// Assume there is only 1 camera and it is rear-facing.
			mIsCameraFrontFacing = false;
			mNumCameras = 1;
			camera = Camera.open();
		}
		final Parameters parameters = camera.getParameters();
		mCameraProjectionAdapter.setCameraParameters(parameters);
		camera.release();
	}

	@Override
	public void onSaveInstanceState(Bundle savedInstanceState) {
		// Save the current camera index.
		savedInstanceState.putInt(STATE_CAMERA_INDEX, mCameraIndex);

		// Save the current filter indices.
		savedInstanceState.putInt(STATE_IMAGE_DETECTION_FILTER_INDEX,
				mImageDetectionFilterIndex);

		super.onSaveInstanceState(savedInstanceState);
	}

	@Override
	public void onPause() {
		if (mOpenCvCameraView != null) {
			mOpenCvCameraView.disableView();
		}
		super.onPause();
		
		if (sensorManager !=null){
		sensorManager.unregisterListener(this);
		sensorManager = null;

		cx = 0;
		cy = 0;
		cz = 0;
		// tvCount.setText("0");
		timeSwap += timeInMillies;
		// startTime = 0;
		myHandler.removeCallbacks(updateTimerMethod);
		
		}
	}

	@Override
	public void onResume() {
		super.onResume();
		OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_2_4_4, this,
				mLoaderCallback);
		mIsMenuLocked = false;

		startTime = SystemClock.uptimeMillis();
		myHandler.postDelayed(updateTimerMethod, 0);

		/*
		 * boolean accelSupported = sensorManager.registerListener(this,
		 * sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
		 * SensorManager.SENSOR_DELAY_NORMAL);
		 * 
		 * if (!accelSupported) { // on accelerometer on this device
		 * sensorManager.unregisterListener(this); //
		 * accuracyLabel.setText("No Accelerometer"); }
		 */

	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		if (mOpenCvCameraView != null) {
			mOpenCvCameraView.disableView();
		}
	}

	@Override
	public boolean onCreateOptionsMenu(final Menu menu) {
		getMenuInflater().inflate(R.menu.activity_camera, menu);
		/*
		 * if (mNumCameras < 2) { // Remove the option to switch cameras, since
		 * there is // only 1. menu.removeItem(R.id.menu_next_camera); }
		 */
		return true;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see android.app.Activity#onOptionsItemSelected(android.view.MenuItem)
	 */
	@Override
	public boolean onOptionsItemSelected(final MenuItem item) {
		if (mIsMenuLocked) {
			return true;
		}
		switch (item.getItemId()) {
		case R.id.menu_next_image_detection_filter:
			mImageDetectionFilterIndex++;
			if (mImageDetectionFilterIndex == 1) {
				Toast.makeText(getApplicationContext(),
						"Recognizing Starry Night Image", Toast.LENGTH_SHORT)
						.show();
			}
			if (mImageDetectionFilterIndex == 2) {
				Toast.makeText(getApplicationContext(),
						"Recognizing Akbar Hunting Image", Toast.LENGTH_SHORT)
						.show();
			}

			if (mImageDetectionFilterIndex == mImageDetectionFilters.length) {
				mImageDetectionFilterIndex = 0;
				Toast.makeText(getApplicationContext(),
						"Please Tap the Tracking Button to Track an Image",
						Toast.LENGTH_LONG).show();
			}
			mARRenderer.filter = mImageDetectionFilters[mImageDetectionFilterIndex];
			return true;

		default:
			return super.onOptionsItemSelected(item);
		}
	}

	@Override
	public void onCameraViewStarted(final int width, final int height) {

	}

	@Override
	public void onCameraViewStopped() {

	}

	@Override
	public Mat onCameraFrame(final CvCameraViewFrame inputFrame) {
		final Mat rgba = inputFrame.rgba();

		// Apply the active filters.
		if (mImageDetectionFilters != null) {
			if (counter % count == 0) {
				mImageDetectionFilters[mImageDetectionFilterIndex].apply(rgba,
						rgba);

				runOnUiThread(new Runnable() {
					@Override
					public void run() {
						tvCount.setText(" Count " + count + " \n" + " Counter "
								+ counter + " \n" + " TotalFrames "
								+ totalFrames);
					}
				});

				Log.d(TAG, "Inside count: " + count + "  Counter " + counter);
			}
			totalFrames = totalFrames + counter;
			counterAdder();

		}

		/*
		 * if (mImageDetectionFilters != null && (cx > cx-1 & cx < cx+1) && (cy
		 * > cy-1 & cy < cy+1) && (cz > cz-1 & cz < cz+1) ) { count = 10; if
		 * (counter == count) {
		 * mImageDetectionFilters[mImageDetectionFilterIndex].apply(rgba, rgba);
		 * }
		 * 
		 * counterAdder();
		 * 
		 * }
		 */

		return rgba;
	}

	private void counterAdder() {

		// tvCount.setText(counter);
		if (counter > 26) {
			counter = 0;

		}
		counter++;
		// count= counter;

	}

	@Override
	public void onAccuracyChanged(Sensor sensor, int accuracy) {

	}

	@Override
	public void onSensorChanged(SensorEvent sensorEvent) {
		if (sensorEvent.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
			long curTime = System.currentTimeMillis();
			// only allow one update every 100ms, otherwise updates
			// come way too fast and the phone gets bogged down
			// with garbage collection
			if (lastUpdate == -1 || (curTime - lastUpdate) > 100) {
				lastUpdate = curTime;

				x = sensorEvent.values[0];
				y = sensorEvent.values[1];
				z = sensorEvent.values[2];

				tvX.setText(String.format("X: %+2.5f (%+2.5f)", (x + cx), cx));
				tvY.setText(String.format("Y: %+2.5f (%+2.5f)", (y + cy), cy));
				tvZ.setText(String.format("Z: %+2.5f (%+2.5f)", (z + cz), cz));

				/*
				 * if ((cx > cx - 0.41 & cx < cx + 0.41) && (cy > cy-1 & cy <
				 * cy+1) && (cz > cz-1 & cz < cz+1) ) { count = 10; } else if
				 * ((cx > cx - 0.3 & cx < cx + 0.3) && (cy > cy-3 & cy < cy+3)
				 * && (cz > cz-3 & cz < cz+3) ) { count = 7; } else if ((cx >=
				 * cx - 0.1 & cx <= cx + 0.1) && (cy > cy-5 & cy < cy+5) && (cz
				 * > cz-5 & cz < cz+5) ) { count = 4; }
				 * 
				 * tvCount.setText("Counter Value: " + count);
				 */
			}
		}

	}

	private Runnable updateTimerMethod = new Runnable() {

		public void run() {
			timeInMillies = SystemClock.uptimeMillis() - startTime;
			finalTime = timeSwap + timeInMillies;

			int seconds = (int) (finalTime / 1000);
			int minutes = seconds / 60;
			seconds = seconds % 60;
			int milliseconds = (int) (finalTime % 1000);
			tvTimer.setText("Timer: " + minutes + ":"
					+ String.format("%02d", seconds) + ":"
					+ String.format("%03d", milliseconds));
			myHandler.postDelayed(this, 0);
		}

	};
}
