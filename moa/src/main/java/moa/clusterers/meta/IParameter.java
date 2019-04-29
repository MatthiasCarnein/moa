package moa.clusterers.meta;

// interface allows us to maintain a single list of parameters
public interface IParameter {
	public void sampleNewConfig(int iter, int nbNewConfigurations, int nbVariable);

	public IParameter copy();

	public String getCLIString();

	public String getCLIValueString();

	public double getValue();

	public String getParameter();
}
