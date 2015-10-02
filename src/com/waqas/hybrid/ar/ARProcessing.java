package com.waqas.hybrid.ar;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.opencv.android.Utils;
import org.opencv.calib3d.Calib3d;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDMatch;
import org.opencv.core.MatOfDouble;
import org.opencv.core.MatOfKeyPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.MatOfPoint3f;
import org.opencv.core.Point;
import org.opencv.core.Point3;
import org.opencv.features2d.DMatch;
import org.opencv.features2d.DescriptorExtractor;
import org.opencv.features2d.DescriptorMatcher;
import org.opencv.features2d.FeatureDetector;
import org.opencv.features2d.KeyPoint;
import org.opencv.highgui.Highgui;
import org.opencv.imgproc.Imgproc;

import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;

import com.waqas.hybrid.adapters.CameraProjectionAdapter;

public class ARProcessing implements ARFilter {

	private static final String TAG = "ARFilterClass";
	private final Mat mReferenceImage;
	private final MatOfKeyPoint mReferenceKeypoints = new MatOfKeyPoint();
	private final Mat mReferenceDescriptors = new Mat();
	// CVType defines the color depth, number of channels, and
	// channel layout in the image.
	private final Mat mReferenceCorners = new Mat(4, 1, CvType.CV_32FC2);

	private final MatOfKeyPoint mSceneKeypoints = new MatOfKeyPoint();
	private final Mat mSceneDescriptors = new Mat();

	private final Mat mGraySrc = new Mat();
	private final MatOfDMatch mMatches = new MatOfDMatch();

	private final FeatureDetector mFeatureDetector = FeatureDetector
			.create(FeatureDetector.ORB);
	private final DescriptorExtractor mDescriptorExtractor = DescriptorExtractor
			.create(DescriptorExtractor.FREAK);
	private final DescriptorMatcher mDescriptorMatcher = DescriptorMatcher
			.create(DescriptorMatcher.BRUTEFORCE_HAMMING);

	private final MatOfDouble mDistCoeffs = new MatOfDouble(0.0, 0.0, 0.0, 0.0);

	private final CameraProjectionAdapter mCameraProjectionAdapter;
	private final MatOfDouble mRVec = new MatOfDouble();
	private final MatOfDouble mTVec = new MatOfDouble();
	private final MatOfDouble mRotation = new MatOfDouble();
	private final float[] mGLPose = new float[16];

	private boolean mTargetFound = false;

	// timing capture
	private long startTime = 0L;
	private Handler myHandler = new Handler();
	long timeInMillies = 0L;
	long timeSwap = 0L;
	long finalTime = 0L;
	String setTime;

	public ARProcessing(final Context context,
			final int referenceImageResourceID,
			final CameraProjectionAdapter cameraProjectionAdapter)
			throws IOException {

		mReferenceImage = Utils.loadResource(context, referenceImageResourceID,
				Highgui.CV_LOAD_IMAGE_COLOR);

		final Mat referenceImageGray = new Mat();
		// converting reference target image into gray before processing
		Imgproc.cvtColor(mReferenceImage, referenceImageGray,
				Imgproc.COLOR_BGR2GRAY);
		Imgproc.cvtColor(mReferenceImage, mReferenceImage,
				Imgproc.COLOR_BGR2RGBA);

		mReferenceCorners.put(0, 0, new double[] { 0.0, 0.0 });
		mReferenceCorners.put(1, 0, new double[] { referenceImageGray.cols(),
				0.0 });
		mReferenceCorners.put(2, 0, new double[] { referenceImageGray.cols(),
				referenceImageGray.rows() });
		mReferenceCorners.put(3, 0,
				new double[] { 0.0, referenceImageGray.rows() });

		// calculate the timing of this method
		startTime = SystemClock.uptimeMillis();
		myHandler.postDelayed(updateTimerMethod, 0);

		mFeatureDetector.detect(referenceImageGray, mReferenceKeypoints);

		// stop timer
		timeSwap += timeInMillies;
		myHandler.removeCallbacks(updateTimerMethod);

		mDescriptorExtractor.compute(referenceImageGray, mReferenceKeypoints,
				mReferenceDescriptors);
		Log.d(TAG, "InitialReference Keypoint:" + mReferenceKeypoints.total());
		Log.d(TAG,
				"InitialReference Descriptors:" + mReferenceDescriptors.total());
		Log.d(TAG, "DetectionRef() Time:" + setTime);
		mCameraProjectionAdapter = cameraProjectionAdapter;
	}

	@Override
	public float[] getGLPose() {
		return (mTargetFound ? mGLPose : null);
	}

	@Override
	public void apply(final Mat src, final Mat dst) {

		// converting the live video frames into gray scale before processing
		Imgproc.cvtColor(src, mGraySrc, Imgproc.COLOR_RGBA2GRAY);

		mFeatureDetector.detect(mGraySrc, mSceneKeypoints);

		mDescriptorExtractor.compute(mGraySrc, mSceneKeypoints,
				mSceneDescriptors);

		mDescriptorMatcher.match(mSceneDescriptors, mReferenceDescriptors,
				mMatches);
		myPoseEstimation();
		draw(src, dst);

		// Log Statements

		Log.d(TAG, "Reference Keypoint:" + mReferenceKeypoints.total());
		Log.d(TAG, "Scene Keypoint:" + mSceneKeypoints.total());
		Log.d(TAG, "Scene Descriptors:" + mSceneDescriptors.total());
	}

	private void myPoseEstimation() {

		List<DMatch> matchesList = mMatches.toList();
		Log.d(TAG, "xGood MatchesList:" + matchesList.size());
		if (matchesList.size() < 4) {
			// There are too few matches to find the pose.
			return;
		}

		List<KeyPoint> referenceKeypointsList = mReferenceKeypoints.toList();

		List<KeyPoint> sceneKeypointsList = mSceneKeypoints.toList();
		Log.d(TAG, "Scene Keypoint to List:" + mSceneKeypoints.toList().size());

		// Calculate the max and min distances between keypoints.
		double maxDist = 0.0;
		double minDist = Double.MAX_VALUE;
		for (DMatch match : matchesList) {
			double dist = match.distance;
			if (dist < minDist) {
				minDist = dist;
			}
			if (dist > maxDist) {
				maxDist = dist;
			}
		}

		// The thresholds for minDist are chosen subjectively
		// based on testing. The unit is not related to pixel
		// distances; it is related to the number of failed tests
		// for similarity between the matched descriptors.
		if (minDist > 50.0) {
			// The target is completely lost.
			mTargetFound = false;
			return;
		} else if (minDist > 25.0) {
			// The target is lost but maybe it is still close.
			// Keep using any previously found pose.
			return;
		}

		// Identify "good" keypoints based on match distance.
		List<Point3> goodReferencePointsList = new ArrayList<Point3>();
		ArrayList<Point> goodScenePointsList = new ArrayList<Point>();
		double maxGoodMatchDist = 1.75 * minDist;
		for (DMatch match : matchesList) {
			if (match.distance < maxGoodMatchDist) {
				Point point = referenceKeypointsList.get(match.trainIdx).pt;
				Point3 point3 = new Point3(point.x, point.y, 0.0);
				goodReferencePointsList.add(point3);
				goodScenePointsList
						.add(sceneKeypointsList.get(match.queryIdx).pt);

				// Log statements
				Log.d(TAG, "Match Distance" + match.distance);
				Log.d(TAG, "Match TrainIdx:" + match.trainIdx);
				Log.d(TAG,
						"Match TrainIdx.pt"
								+ referenceKeypointsList.get(match.trainIdx).pt);
				Log.d(TAG, "Match QueryIdx:" + match.queryIdx);
				Log.d(TAG,
						"Match QueryIdx.pt"
								+ sceneKeypointsList.get(match.queryIdx).pt);
				Log.d(TAG, "ReferenceKeypointList.size:"
						+ referenceKeypointsList.size());
				Log.d(TAG,
						"SceneKeypointList.size:" + sceneKeypointsList.size());
			}
		}

		if (goodReferencePointsList.size() < 5
				|| goodScenePointsList.size() < 5) {
			// There are too few good points to find the pose.
			Log.d(TAG, "There are too few good points to find the pose. <4");
			return;
		}

		MatOfPoint3f goodReferencePoints = new MatOfPoint3f();
		goodReferencePoints.fromList(goodReferencePointsList);

		MatOfPoint2f goodScenePoints = new MatOfPoint2f();
		goodScenePoints.fromList(goodScenePointsList);

		MatOfDouble projection = mCameraProjectionAdapter.getProjectionCV();
		Calib3d.solvePnP(goodReferencePoints, goodScenePoints, projection,
				mDistCoeffs, mRVec, mTVec);
		Log.d(TAG, "GoodReferenceKeypointList.size:"
				+ goodReferencePoints.toList().size());
		Log.d(TAG, "GoodSceneKeypointList.size:"
				+ goodScenePoints.toList().size());

		double[] rVecArray = mRVec.toArray();
		rVecArray[1] *= -1.0;
		rVecArray[2] *= -1.0;
		mRVec.fromArray(rVecArray);

		Calib3d.Rodrigues(mRVec, mRotation);

		double[] tVecArray = mTVec.toArray();

		mGLPose[0] = (float) mRotation.get(0, 0)[0];
		mGLPose[1] = (float) mRotation.get(1, 0)[0];
		mGLPose[2] = (float) mRotation.get(2, 0)[0];
		mGLPose[3] = 0f;
		mGLPose[4] = (float) mRotation.get(0, 1)[0];
		mGLPose[5] = (float) mRotation.get(1, 1)[0];
		mGLPose[6] = (float) mRotation.get(2, 1)[0];
		mGLPose[7] = 0f;
		mGLPose[8] = (float) mRotation.get(0, 2)[0];
		mGLPose[9] = (float) mRotation.get(1, 2)[0];
		mGLPose[10] = (float) mRotation.get(2, 2)[0];
		mGLPose[11] = 0f;
		mGLPose[12] = (float) tVecArray[0];
		mGLPose[13] = -(float) tVecArray[1];
		mGLPose[14] = -(float) tVecArray[2];
		mGLPose[15] = 1f;

		mTargetFound = true;
	}

	protected void draw(Mat src, Mat dst) {

		if (dst != src) {
			src.copyTo(dst);
		}

		if (!mTargetFound) {
			// The target has not been found.

			// Draw a thumbnail of the target in the upper-left
			// corner so that the user knows what it is.

			int height = mReferenceImage.height();
			int width = mReferenceImage.width();
			int maxDimension = Math.min(dst.width(), dst.height()) / 2;
			double aspectRatio = width / (double) height;
			if (height > width) {
				height = maxDimension;
				width = (int) (height * aspectRatio);
			} else {
				width = maxDimension;
				height = (int) (width / aspectRatio);
			}
			Mat dstROI = dst.submat(0, height, 0, width);
			Imgproc.resize(mReferenceImage, dstROI, dstROI.size(), 0.0, 0.0,
					Imgproc.INTER_AREA);

		}
	}

	private Runnable updateTimerMethod = new Runnable() {

		public void run() {
			finalTime = SystemClock.uptimeMillis() - startTime;
			// timeInMillies = SystemClock.uptimeMillis() - startTime;
			// finalTime = timeSwap + timeInMillies;

			int seconds = (int) (finalTime / 1000);
			int minutes = seconds / 60;
			seconds = seconds % 60;
			int milliseconds = (int) (finalTime % 1000);
			setTime = ("" + minutes + ":" + String.format("%02d", seconds)
					+ ":" + String.format("%03d", milliseconds));

			myHandler.postDelayed(this, 0);
		}

	};

}
