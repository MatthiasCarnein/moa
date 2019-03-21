package moa.clusterers.meta;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.util.ArrayList;
import com.github.javacliparser.FileOption;
import com.github.javacliparser.ListOption;
import com.github.javacliparser.Option;
import com.google.gson.Gson;
import com.yahoo.labs.samoa.instances.Attribute;
import com.yahoo.labs.samoa.instances.DenseInstance;
import com.yahoo.labs.samoa.instances.Instance;
import com.yahoo.labs.samoa.instances.Instances;
import moa.classifiers.meta.AdaptiveRandomForestRegressor;
import moa.cluster.Clustering;
import moa.clusterers.AbstractClusterer;
import moa.clusterers.Clusterer;
import moa.clusterers.denstream.WithDBSCAN;
import moa.core.Measurement;
import moa.core.ObjectRepository;
import moa.evaluation.SilhouetteCoefficient;
import moa.gui.visualization.DataPoint;
import moa.options.ClassOption;
import moa.streams.clustering.RandomRBFGeneratorEvents;
import moa.tasks.TaskMonitor;

// Classes to read json settings
class ParameterSettings {
	public String parameter;
	public double value;
}

class AlgorithmSettings {
	public String algorithm;
	public ParameterSettings[] parameters;
}

class GeneralSettings {
	public int windowSize;
	public AlgorithmSettings[] algorithms;
}

public abstract class EnsembleClustererAbstract extends AbstractClusterer {

	private static final long serialVersionUID = 1L;

	int instancesSeen;
	protected Clusterer[] ensemble;
	int bestModel;
	ArrayList<DataPoint> windowPoints;
	AdaptiveRandomForestRegressor ARFreg;
	int windowSize;

	public FileOption fileOption = new FileOption("ConfigurationFile", 'f', "Configuration file in json format.",
			"settings.json", ".json", false);

	public void init() {
		this.fileOption.getFile();
	}

	@Override
	public boolean isRandomizable() {
		return false;
	}

	@Override
	public double[] getVotesForInstance(Instance inst) {
		return null;
	}

	@Override
	public Clustering getClusteringResult() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void resetLearningImpl() {

		this.instancesSeen = 0;
		this.bestModel = 0;
		this.windowPoints = new ArrayList<DataPoint>(this.windowSize);

		this.ARFreg = new AdaptiveRandomForestRegressor(); // create regressor
		this.ARFreg.prepareForUse();

		for (int i = 0; i < this.ensemble.length; i++) {
			this.ensemble[i].resetLearning();
		}
	}

	@Override
	public void trainOnInstanceImpl(Instance inst) {
		DataPoint point = new DataPoint(inst, instancesSeen); // create data points from instance
		this.windowPoints.add(point); // remember points of the current window

		// train all models
		for (int i = 0; i < this.ensemble.length; i++) {
			this.ensemble[i].trainOnInstance(inst);
		}

		// every windowSize we update the configurations
		if (this.instancesSeen % this.windowSize == 0) {
			updateConfiguration();
			windowPoints.clear(); // flush the current window
			System.out.println("--------------");
		}

		this.instancesSeen++;
	}

	protected void updateConfiguration() {
		// init evaluation measure
		SilhouetteCoefficient silh = new SilhouetteCoefficient();

		// parameter settings are the attributes, class label is the performance
		ArrayList<Attribute> attributes = new ArrayList<Attribute>();
		for (int j = 0; j < 1; j++) {
			attributes.add(new Attribute("att" + (j + 1)));
		}
		attributes.add(new Attribute("class1"));

		double maxVal = -1 * Double.MAX_VALUE;
		for (int i = 0; i < this.ensemble.length; i++) {
			// get current macro clusters
			Clustering result = this.ensemble[i].getClusteringResult();

			// evaluate clustering if we have one
			// if no algorithm produces a clustering, the old clusterer will remain active
			if (result != null) {
				// compute silhouette width
				silh.evaluateClustering(result, null, windowPoints);
				double performance = silh.getLastValue(0);
				System.out.println(
						"Result " + this.ensemble[i].getCLICreationString(Clusterer.class) + ":\t" + performance);
//				System.out.println(ClassOption.stripPackagePrefix(this.ensemble[i].getClass().getName(), Clusterer.class)); // print class
//				System.out.println(this.ensemble[i].getOptions().getAsCLIString()); // print non-default options

				// find best clustering result
				if (performance > maxVal) {
					maxVal = performance;
					this.bestModel = i; // the clusterer with the best result becomes the active one
				}

				// create new instance based on settings and performance to train regressor
				double[] values = { ((WithDBSCAN) this.ensemble[i]).epsilonOption.getValue(), performance };
				Instance inst = new DenseInstance(1.0, values);

				// add header to dataset
				Instances dataset = new Instances(null, attributes, 0);
				dataset.setClassIndex(dataset.numAttributes() - 1);

				inst.setDataset(dataset);

				// train adaptive random forest regressor based on performance of model
				this.ARFreg.trainOnInstanceImpl(inst);
			}
		}

		// predict performance of new configuration
		double[] vals = { 0.07, 0 }; // TODO adding a dummy class for now because it crashes otherwise
		Instance newInst = new DenseInstance(1.0, vals);
		Instances newDataset = new Instances(null, attributes, 0);
		newDataset.setClassIndex(newDataset.numAttributes() - 1);
		newInst.setDataset(newDataset);

		double prediction = this.ARFreg.getVotesForInstance(newInst)[0];
		System.out.println("-> Prediction -e=" + vals[0] + ":\t" + prediction);
	}

	@Override
	protected Measurement[] getModelMeasurementsImpl() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void getModelDescription(StringBuilder out, int indent) {
		// TODO Auto-generated method stub
	}

	public void prepareForUseImpl(TaskMonitor monitor, ObjectRepository repository) {

		// read settings from json
		try {
			BufferedReader bufferedReader = new BufferedReader(new FileReader(fileOption.getValue()));
			Gson gson = new Gson();
			GeneralSettings settings = gson.fromJson(bufferedReader, GeneralSettings.class);

			this.windowSize = settings.windowSize;

			this.ensemble = new Clusterer[settings.algorithms.length];
			// for all algorithms
			for (int i = 0; i < settings.algorithms.length; i++) {

				// construct CLI string
				StringBuilder commandLine = new StringBuilder();
				commandLine.append(settings.algorithms[i].algorithm); // add algorithm class
				for (ParameterSettings option : settings.algorithms[i].parameters) {
					commandLine.append(" ");
					commandLine.append("-" + option.parameter); // as well as all parameters
					commandLine.append(" " + option.value);
				}
				System.out.println("Initialise: " + commandLine.toString());

				// create new clusterer from CLI string
				ClassOption opt = new ClassOption("", ' ', "", Clusterer.class, commandLine.toString());
				this.ensemble[i] = (Clusterer) opt.materializeObject(monitor, repository);
			}
		} catch (

		FileNotFoundException e) {
			e.printStackTrace();
		}
		super.prepareForUseImpl(monitor, repository);

	}

	public static void main(String[] args) {
		EnsembleClustererBlast algorithm = new EnsembleClustererBlast();
		RandomRBFGeneratorEvents stream = new RandomRBFGeneratorEvents();
		stream.prepareForUse();
		algorithm.prepareForUse();
		for (int i = 0; i < 100000; i++) {
			Instance inst = stream.nextInstance().getData();
			algorithm.trainOnInstanceImpl(inst);
		}
		algorithm.getClusteringResult();

//		System.out.println("-------------");
//		
//		EnsembleClusterer algorithm2 = new EnsembleClusterer();
//		RandomRBFGeneratorEvents stream2 = new RandomRBFGeneratorEvents();
//		stream2.prepareForUse();
//		algorithm2.prepareForUse();
//		for(int i=0; i<100000; i++) {
//			Instance inst = stream2.nextInstance().getData();
//			algorithm2.trainOnInstanceImpl(inst);
//		}
//		algorithm2.getClusteringResult();

	}

}
