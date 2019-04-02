package moa.clusterers.meta;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Arrays;
import com.github.javacliparser.FileOption;
import com.google.gson.Gson;
import com.yahoo.labs.samoa.instances.Attribute;
import com.yahoo.labs.samoa.instances.DenseInstance;
import com.yahoo.labs.samoa.instances.Instance;
import com.yahoo.labs.samoa.instances.Instances;
import moa.classifiers.meta.AdaptiveRandomForestRegressor;
import moa.cluster.Clustering;
import moa.clusterers.AbstractClusterer;
import moa.clusterers.Clusterer;
import moa.core.Measurement;
import moa.core.ObjectRepository;
import moa.evaluation.SilhouetteCoefficient;
import moa.gui.visualization.DataPoint;
import moa.options.ClassOption;
import moa.streams.clustering.RandomRBFGeneratorEvents;
import moa.tasks.TaskMonitor;

// The main flow is as follow:
// A json is read which contains the main settings and starting configurations / algorithms
// The json is used to initialize the three configuration classes below (same structure as json)
// From the json, we create the Algorithm and Parameter classes (depending on the type) which form the ensemble of clusterers
// These classes are then used to cluster and evaluate the algorithms
// When a new configuration is required, a parameter configuration is copied and the parameters manipulated

// these classes are initialised by gson and contain the starting configurations
// This class contains the individual parameter settings (such as limits and current value)
class ParameterConfiguration {
	public String parameter;
	public Object value;
	public Object[] range;
	public String type;
}

// This class contains the settings of an algorithm (such as name) as well as an
// array of Parameter Settings
class AlgorithmConfiguration {
	public String algorithm;
	public ParameterConfiguration[] parameters;
}

// This contains the general settings (such as the max ensemble size) as well as
// an array of Algorithm Settings
class GeneralConfiguration {
	public int windowSize;
	public int ensembleSize;
	public int newConfigurations;
	public AlgorithmConfiguration[] algorithms;
}

// interface allows us to maintain a single list of parameters
interface IParameter {
	public void sampleNewConfig(int nbNewConfigurations, int nbVariable);

	public IParameter duplicate();

	public String getCLIString();

	public double getValue();

	public String getParameter();
}

// the representation of a numerical / real parameter
class NumericalParameter implements IParameter {
	private String parameter;
	private double value;
	private double[] range;
	private double std;
	private Attribute attribute;

	public NumericalParameter(NumericalParameter x) {
		this.parameter = x.parameter;
		this.value = x.value;
		this.range = x.range.clone();
		this.std = x.std;
		this.attribute = new Attribute(x.parameter);
	}

	public NumericalParameter(ParameterConfiguration x) {
		this.parameter = x.parameter;
		this.value = (double) x.value;
		this.range = new double[x.range.length];
		for (int i = 0; i < x.range.length; i++) {
			range[i] = (double) x.range[i];
		}
		this.std = (this.range[1] - this.range[0]) / 2;
		this.attribute = new Attribute(x.parameter);
	}

	public NumericalParameter duplicate() {
		return new NumericalParameter(this);
	}

	public String getCLIString() {
		return ("-" + this.parameter + " " + this.value);
	}

	public double getValue() {
		return this.value;
	}

	public String getParameter() {
		return this.parameter;
	}

	public void sampleNewConfig(int nbNewConfigurations, int nbVariable) {
		// update configuration
		// for numeric features use truncated normal distribution
		TruncatedNormal trncnormal = new TruncatedNormal(this.value, this.std, this.range[0], this.range[1]);
		double newValue = trncnormal.sample();

		System.out.println("Sample new configuration for numerical parameter -" + this.parameter + " with mean: "
				+ this.value + ", std: " + this.std + ", lb: " + this.range[0] + ", ub: " + this.range[1] + "\t=>\t -"
				+ this.parameter + " " + newValue);

		this.value = newValue;

		// adapt distribution
		this.std = this.std * (Math.pow((1.0 / nbNewConfigurations), (1.0 / nbVariable)));
	}
}

// the representation of an integer parameter
class IntegerParameter implements IParameter {
	private String parameter;
	private int value;
	private int[] range;
	private double std;
	private Attribute attribute;

	public IntegerParameter(IntegerParameter x) {
		this.parameter = x.parameter;
		this.value = x.value;
		this.range = x.range.clone();
		this.std = x.std;
		this.attribute = x.attribute;// new Attribute(x.parameter);
	}

	public IntegerParameter(ParameterConfiguration x) {
		this.parameter = x.parameter;
		this.value = (int) (double) x.value; // TODO fix casts
		this.range = new int[x.range.length];
		for (int i = 0; i < x.range.length; i++) {
			range[i] = (int) (double) x.range[i];
		}
		this.std = (this.range[1] - this.range[0]) / 2;
		this.attribute = new Attribute(x.parameter);
	}

	public IntegerParameter duplicate() {
		return new IntegerParameter(this);
	}

	public String getCLIString() {
		return ("-" + this.parameter + " " + this.value);
	}

	public double getValue() {
		return this.value;
	}

	public String getParameter() {
		return this.parameter;
	}

	public void sampleNewConfig(int nbNewConfigurations, int nbVariable) {
		// update configuration
		// for integer features use truncated normal distribution
		TruncatedNormal trncnormal = new TruncatedNormal(this.value, this.std, this.range[0], this.range[1]);
		int newValue = (int) Math.round(trncnormal.sample());
		System.out.println("Sample new configuration for integer parameter -" + this.parameter + " with mean: "
				+ this.value + ", std: " + this.std + ", lb: " + this.range[0] + ", ub: " + this.range[1] + "\t=>\t -"
				+ this.parameter + " " + newValue);

		this.value = newValue;
		// adapt distribution
		this.std = this.std * (Math.pow((1.0 / nbNewConfigurations), (1.0 / nbVariable)));
	}
}

// the representation of a categorical / nominal parameter
class CategoricalParameter implements IParameter {
	private String parameter;
	private int numericValue;
	private String value;
	private String[] range;
	private Attribute attribute;
	private ArrayList<Double> probabilities;

	public CategoricalParameter(CategoricalParameter x) {
		this.parameter = x.parameter;
		this.numericValue = x.numericValue;
		this.value = x.value;
		this.range = x.range.clone();
		this.attribute = x.attribute;
		this.probabilities = new ArrayList<Double>(x.probabilities);
	}

	public CategoricalParameter(ParameterConfiguration x) {
		this.parameter = x.parameter;
		this.value = String.valueOf(x.value);
		this.range = new String[x.range.length];
		for (int i = 0; i < x.range.length; i++) {
			range[i] = String.valueOf(x.range[i]);
			if (this.range[i].equals(this.value)) {
				this.numericValue = i; // get index of init value
			}
		}
		this.attribute = new Attribute(x.parameter, Arrays.asList(range));
		this.probabilities = new ArrayList<Double>(x.range.length);
		for (int i = 0; i < x.range.length; i++) {
			this.probabilities.add(1.0 / x.range.length); // equal probabilities
		}
	}

	public CategoricalParameter duplicate() {
		return new CategoricalParameter(this);
	}

	public String getCLIString() {
		return ("-" + this.parameter + " " + this.value);
	}

	public double getValue() {
		return this.numericValue;
	}

	public String getParameter() {
		return this.parameter;
	}

	public String[] getRange() {
		return this.range;
	}

	public void sampleNewConfig(int nbNewConfigurations, int nbVariable) {
		// update configuration
		this.numericValue = EnsembleClustererAbstract.sampleProportionally(this.probabilities);
		String newValue = this.range[this.numericValue];

		System.out.print("Sample new configuration for nominal parameter -" + this.parameter + "with probabilities");
		for (int i = 0; i < this.probabilities.size(); i++) {
			System.out.print(" " + this.probabilities.get(i));
		}
		System.out.println("\t=>\t -" + this.parameter + " " + newValue);
		this.value = newValue;

		// adapt distribution
		for (int i = 0; i < this.probabilities.size(); i++) {
			// TODO not directly transferable, (1-((iter -1) / maxIter))
			this.probabilities.set(i, this.probabilities.get(i) * (1.0 - ((10 - 1.0) / 100)));
		}
		this.probabilities.set(this.numericValue, (this.probabilities.get(this.numericValue) + ((10 - 1.0) / 100)));

		// divide by sum
		double sum = 0.0;
		for (int i = 0; i < this.probabilities.size(); i++) {
			sum += this.probabilities.get(i);
		}
		for (int i = 0; i < this.probabilities.size(); i++) {
			this.probabilities.set(i, this.probabilities.get(i) / sum);
		}
	}
}

// the representation of a boolean / binary parameter
class BooleanParameter implements IParameter {
	private String parameter;
	private int numericValue;
	private String value;
	private String[] range = { "false", "true" };
	private Attribute attribute;
	private ArrayList<Double> probabilities;

	public BooleanParameter(BooleanParameter x) {
		this.parameter = x.parameter;
		this.numericValue = x.numericValue;
		this.value = x.value;
		this.range = x.range.clone();
		this.attribute = x.attribute;
		this.probabilities = new ArrayList<Double>(x.probabilities);
	}

	public BooleanParameter(ParameterConfiguration x) {
		this.parameter = x.parameter;
		this.value = String.valueOf(x.value);
		for (int i = 0; i < this.range.length; i++) {
			if (this.range[i].equals(this.value)) {
				this.numericValue = i; // get index of init value
			}
		}
		this.attribute = new Attribute(x.parameter);

		this.probabilities = new ArrayList<Double>(2);
		for (int i = 0; i < 2; i++) {
			this.probabilities.add(0.5); // equal probabilities
		}
	}

	public BooleanParameter duplicate() {
		return new BooleanParameter(this);
	}

	public String getCLIString() {
		// if option is set
		if (this.numericValue == 1) {
			return ("-" + this.parameter); // only the parameter
		}
		return "";
	}

	public double getValue() {
		return this.numericValue;
	}

	public String getParameter() {
		return this.parameter;
	}

	public String[] getRange() {
		return this.range;
	}

	public void sampleNewConfig(int nbNewConfigurations, int nbVariable) {
		// update configuration
		this.numericValue = EnsembleClustererAbstract.sampleProportionally(this.probabilities);
		String newValue = this.range[this.numericValue];
		System.out.print("Sample new configuration for boolean parameter -" + this.parameter + " with probabilities");
		for (int i = 0; i < this.probabilities.size(); i++) {
			System.out.print(" " + this.probabilities.get(i));
		}
		System.out.println("\t=>\t -" + this.parameter + " " + newValue);
		this.value = newValue;

		// adapt distribution
		for (int i = 0; i < this.probabilities.size(); i++) {
			// TODO not directly transferable, (1-((iter -1) / maxIter))
			this.probabilities.set(i, this.probabilities.get(i) * (1.0 - ((10 - 1.0) / 100)));
		}
		this.probabilities.set(this.numericValue, (this.probabilities.get(this.numericValue) + ((10 - 1.0) / 100)));

		// divide by sum
		double sum = 0.0;
		for (int i = 0; i < this.probabilities.size(); i++) {
			sum += this.probabilities.get(i);
		}
		for (int i = 0; i < this.probabilities.size(); i++) {
			this.probabilities.set(i, this.probabilities.get(i) / sum);
		}
	}
}

// the representation of an integer parameter
class OrdinalParameter implements IParameter {
	private String parameter;
	private String value;
	private int numericValue;
	private String[] range;
	private double std;
	private Attribute attribute;

	// copy constructor
	public OrdinalParameter(OrdinalParameter x) {
		this.parameter = x.parameter;
		this.value = x.value;
		this.numericValue = x.numericValue;
		this.range = x.range.clone();
		this.std = x.std;
		this.attribute = x.attribute;
	}

	public OrdinalParameter duplicate() {
		return new OrdinalParameter(this);
	}

	public String getCLIString() {
		return ("-" + this.parameter + " " + this.value);
	}

	public double getValue() {
		return this.numericValue;
	}

	public String getParameter() {
		return this.parameter;
	}

	// init constructor
	public OrdinalParameter(ParameterConfiguration x) {
		this.parameter = x.parameter;
		this.value = String.valueOf(x.value);
		this.range = new String[x.range.length];
		for (int i = 0; i < x.range.length; i++) {
			range[i] = String.valueOf(x.range[i]);
			if (this.range[i].equals(this.value)) {
				this.numericValue = i; // get index of init value
			}
		}
		this.std = (this.range.length - 0) / 2;
		this.attribute = new Attribute(x.parameter);

	}

	public void sampleNewConfig(int nbNewConfigurations, int nbVariable) {
		// update configuration
		// treat index of range as integer parameter
		TruncatedNormal trncnormal = new TruncatedNormal(this.numericValue, this.std, (double) (this.range.length - 1),
				0.0); // limits are the indexes of the range
		int newValue = (int) Math.round(trncnormal.sample());
		System.out.println("Sample new configuration for ordinal parameter -" + this.parameter + " with mean: "
				+ this.numericValue + ", std: " + this.std + ", lb: " + 0 + ", ub: " + (this.range.length - 1)
				+ "\t=>\t -" + this.parameter + " " + this.range[newValue] + " (" + newValue + ")");

		this.numericValue = newValue;
		this.value = this.range[this.numericValue];

		// adapt distribution
		this.std = this.std * (Math.pow((1.0 / nbNewConfigurations), (1.0 / nbVariable)));
	}

}

class Algorithm {
	public String algorithm;
	public ArrayList<IParameter> parameters;
	public Clusterer clusterer;
	public ArrayList<Attribute> attributes;

	// copy constructor
	public Algorithm(Algorithm x) {

		// make a (mostly) deep copy of the algorithm
		this.algorithm = x.algorithm;
		this.attributes = x.attributes; // this is a reference since we dont manipulate the attributes
		this.parameters = new ArrayList<IParameter>(x.parameters.size());
		for (IParameter param : x.parameters) {
			this.parameters.add(param.duplicate());
		}
		// init(); // we dont initialise here because we want to manipulate the
		// parameters first
	}

	// init constructor
	public Algorithm(AlgorithmConfiguration x) {

		this.algorithm = x.algorithm;
		this.parameters = new ArrayList<IParameter>();

		this.attributes = new ArrayList<Attribute>();
		for (ParameterConfiguration paramConfig : x.parameters) {
			if (paramConfig.type.equals("numeric")) {
				NumericalParameter param = new NumericalParameter(paramConfig);
				this.parameters.add(param);
				this.attributes.add(new Attribute(param.getParameter()));
			} else if (paramConfig.type.equals("integer")) {
				IntegerParameter param = new IntegerParameter(paramConfig);
				this.parameters.add(param);
				this.attributes.add(new Attribute(param.getParameter()));
			} else if (paramConfig.type.equals("nominal")) {
				CategoricalParameter param = new CategoricalParameter(paramConfig);
				this.parameters.add(param);
				this.attributes.add(new Attribute(param.getParameter(), Arrays.asList(param.getRange())));
			} else if (paramConfig.type.equals("boolean")) {
				BooleanParameter param = new BooleanParameter(paramConfig);
				this.parameters.add(param);
				this.attributes.add(new Attribute(param.getParameter(), Arrays.asList(param.getRange())));
			} else if (paramConfig.type.equals("ordinal")) {
				OrdinalParameter param = new OrdinalParameter(paramConfig);
				this.parameters.add(param);
				this.attributes.add(new Attribute(param.getParameter()));
			} else {
				throw new RuntimeException("Unknown parameter type: '" + paramConfig.type
						+ "'. Available options are 'numeric', 'integer', 'nominal', 'boolean' or 'ordinal'");
			}
		}
		init();
	}

	public void init() {
		// initialise a new algorithm using the Command Line Interface (CLI)
		// construct CLI string from settings, e.g. denstream.WithDBSCAN -e 0.08 -b 0.3
		StringBuilder commandLine = new StringBuilder();
		commandLine.append(this.algorithm); // first the algorithm class
		for (IParameter param : this.parameters) {
			commandLine.append(" ");
			commandLine.append(param.getCLIString());
		}
		System.out.println("Initialise: " + commandLine.toString());

		// create new clusterer from CLI string
		ClassOption opt = new ClassOption("", ' ', "", Clusterer.class, commandLine.toString());
		this.clusterer = (Clusterer) opt.materializeObject(null, null);
		this.clusterer.prepareForUse();
	}

	public void sampleNewConfig(int nbNewConfigurations) {
		// sample new configuration from the parent
		for (IParameter param : this.parameters) {
			param.sampleNewConfig(nbNewConfigurations, this.attributes.size());
		}
	}

	public double[] getParamVector() {
		double[] params = new double[this.attributes.size() + 1];
		int pos = 0;
		for (IParameter param : this.parameters) {
			params[pos++] = param.getValue();
		}
		return params;
	}
}

public abstract class EnsembleClustererAbstract extends AbstractClusterer {

	private static final long serialVersionUID = 1L;

	int iteration;
	int instancesSeen;
	int currentEnsembleSize;
	int bestModel;
	ArrayList<Algorithm> ensemble;
	ArrayList<DataPoint> windowPoints;
	AdaptiveRandomForestRegressor ARFreg;
	GeneralConfiguration settings;

	// the file option dialogue in the UI
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
		this.windowPoints = new ArrayList<DataPoint>(this.settings.windowSize);

		// create or reset regressor
		this.ARFreg = new AdaptiveRandomForestRegressor();
		this.ARFreg.prepareForUse();

		// reset individual clusterers
		for (int i = 0; i < this.ensemble.size(); i++) {
			this.ensemble.get(i).clusterer.resetLearning();
		}
	}

	@Override
	public void trainOnInstanceImpl(Instance inst) {
		DataPoint point = new DataPoint(inst, instancesSeen); // create data points from instance
		this.windowPoints.add(point); // remember points of the current window
		this.instancesSeen++;

		// train all models with the instance
		for (int i = 0; i < this.ensemble.size(); i++) {
			this.ensemble.get(i).clusterer.trainOnInstance(inst);
		}

		// every windowSize we update the configurations
		if (this.instancesSeen % this.settings.windowSize == 0) {
			System.out.println("\n-------------- Processed " + instancesSeen + " Instances --------------");
			updateConfiguration(); // update configuration
			windowPoints.clear(); // flush the current window
		}
	}

	protected void updateConfiguration() {
		// init evaluation measure (silhouette for now)
		SilhouetteCoefficient silh = new SilhouetteCoefficient();
		// train the random forest regressor based on the configuration performance
		// and find the best performing algorithm
		evaluatePerformance(silh);

		System.out.println("Clusterer " + this.bestModel + " is the active clusterer.");

		// generate a new configuration and predict its performance using the random
		// forest regressor
		predictConfiguration(silh);
	}

	protected void evaluatePerformance(SilhouetteCoefficient silh) {

		double maxVal = -1 * Double.MAX_VALUE;
		for (int i = 0; i < this.ensemble.size(); i++) {
			// get macro clusters of this clusterer
			Clustering result = this.ensemble.get(i).clusterer.getClusteringResult();

			// evaluate clustering using silhouette width
			silh.evaluateClustering(result, null, windowPoints);
			double performance = silh.getLastValue(0);
			System.out.println(i + ") " + this.ensemble.get(i).clusterer.getCLICreationString(Clusterer.class)
					+ ":\t => \t Silhouette: " + performance);

			// find best clustering result among all algorithms
			if (performance > maxVal) {
				maxVal = performance;
				this.bestModel = i; // the clusterer with the best result becomes the active one
			}

			double[] params = this.ensemble.get(i).getParamVector();

			params[params.length - 1] = performance; // add performance as class
			Instance inst = new DenseInstance(1.0, params);

			// add header to dataset TODO: do we need an attribute for the class label?
			Instances dataset = new Instances(null, this.ensemble.get(i).attributes, 0);
			dataset.setClassIndex(dataset.numAttributes()); // set class index to our performance feature
			inst.setDataset(dataset);

			// train adaptive random forest regressor based on performance of model
			this.ARFreg.trainOnInstanceImpl(inst);
		}
	}

	// predict performance of new configuration
	protected void predictConfiguration(SilhouetteCoefficient silh) {

		// sample a parent configuration proportionally to its performance from the
		// ensemble
		ArrayList<Double> silhs = silh.getAllValues(0);

		for (int z = 0; z < this.settings.newConfigurations; z++) {

			// copy existing clusterer configuration
			int parentIdx = EnsembleClustererAbstract.sampleProportionally(silhs);
			System.out.println("Selected Configuration " + parentIdx + " as parent: "
					+ this.ensemble.get(parentIdx).clusterer.getCLICreationString(Clusterer.class));
			Algorithm newAlgorithm = new Algorithm(this.ensemble.get(parentIdx));

			// sample new configuration from the parent
			newAlgorithm.sampleNewConfig(this.settings.newConfigurations);
			newAlgorithm.init();

			double[] params = newAlgorithm.getParamVector();
			Instance newInst = new DenseInstance(1.0, params);
			Instances newDataset = new Instances(null, newAlgorithm.attributes, 0);
			newDataset.setClassIndex(newDataset.numAttributes());
			newInst.setDataset(newDataset);

			double prediction = this.ARFreg.getVotesForInstance(newInst)[0];
			System.out.println("Predict: " + newAlgorithm.clusterer.getCLICreationString(Clusterer.class) + "\t => \t Silhouette: " + prediction);

			// random forest only works with at least two training samples
			if (Double.isNaN(prediction)) {
				return;
			}

			// if we still have open slots in the ensemble (not full)
			if (this.ensemble.size() < this.settings.ensembleSize) {
				System.out.println("Ensemble not full. Add configuration as new algorithm.");

				// add to ensemble
				this.ensemble.add(newAlgorithm);

				// update current silhouettes with the prediction
				silhs.add(prediction);

			} else if (prediction > silh.getMinValue(0)) {
				// if the predicted performance is better than the one we have in the ensemble

				// proportionally sample a configuration that will be replaced
				int replaceIdx = EnsembleClustererAbstract.sampleProportionally(silhs);
				System.out.println(
						"Ensemble already full but new configuration is promising! Replace algorithm: " + replaceIdx);

				// replace in ensemble
				this.ensemble.set(replaceIdx, newAlgorithm);

				// update current silhouettes with the prediction
				silhs.set(replaceIdx, prediction);
			}
		}

	}

	// sample an index from a list of values, proportionally to the respective value
	static int sampleProportionally(ArrayList<Double> values) {
		double completeWeight = 0.0;
		for (Double value : values)
			completeWeight += value;

		double r = Math.random() * completeWeight;
		double countWeight = 0.0;
		for (int j = 0; j < values.size(); j++) {
			countWeight += values.get(j);
			if (countWeight >= r)
				return j;
		}
		throw new RuntimeException("Sampling failed");
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

		try {
			// read settings from json
			BufferedReader bufferedReader = new BufferedReader(new FileReader(fileOption.getValue()));
			Gson gson = new Gson();
			// store settings in dedicated class structure
			this.settings = gson.fromJson(bufferedReader, GeneralConfiguration.class);

			// also create the ensemble which can be larger than the provided (starting)
			// configurations
			this.ensemble = new ArrayList<Algorithm>(this.settings.ensembleSize);
			// copy and initialise the provided starting configurations in the ensemble
			for (int i = 0; i < this.settings.algorithms.length; i++) {
				this.ensemble.add(new Algorithm(this.settings.algorithms[i]));
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
		for (int i = 0; i < 1000000; i++) {
			Instance inst = stream.nextInstance().getData();
			algorithm.trainOnInstanceImpl(inst);
		}
		algorithm.getClusteringResult();

		System.out.println("-------------");

		EnsembleClusterer algorithm2 = new EnsembleClusterer();
		RandomRBFGeneratorEvents stream2 = new RandomRBFGeneratorEvents();
		stream2.prepareForUse();
		algorithm2.prepareForUse();
		for (int i = 0; i < 1000000; i++) {
			Instance inst = stream2.nextInstance().getData();
			algorithm2.trainOnInstanceImpl(inst);
		}
		algorithm2.getClusteringResult();

	}

}
// System.out.println(ClassOption.stripPackagePrefix(this.ensemble[i].getClass().getName(),
// Clusterer.class)); // print class
// System.out.println(this.ensemble[i].getOptions().getAsCLIString()); // print
// non-default options
