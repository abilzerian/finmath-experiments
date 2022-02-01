package net.finmath.experiments.dice.submodels;

import java.util.function.BiFunction;

import net.finmath.experiments.LinearAlgebra;

/**
 * the evolution of the carbon concentration.
 * \(
 * 	M(t_{i+1}) = \Phi M(t_{i}) + emission
 * \)
 * 
 * Unit conversions
 * <ul>
 * 	<li>1 t Carbon = 3.666 t CO2</li>
 * </lui>
 * 
 * Note: The function depends on the time step size.
 * TODO Fix time stepping
 * 
 * @author Christian Fries
 */
public class EvolutionOfCarbonConcentration implements BiFunction<CarbonConcentration, Double, CarbonConcentration> {

	private static double timeStepSize = 5.0;	// time step in the original model (should become a parameter)

	private static double[][] transitionMatrixDefault;
	static {
	    double b12 = 0.12;		// scale
	    double b23 = 0.007;		// scale
	    double mateq = 588;
	    double mueq = 360;
	    double mleq = 1720;

	    double zeta11 = 1 - b12;
	    double zeta21 = b12;
	    double zeta12 = (mateq/mueq)*zeta21;
	    double zeta22 = 1 - zeta12 - b23;
	    double zeta32 = b23;
	    double zeta23 = zeta32*(mueq/mleq);
	    double zeta33 = 1 - zeta23;

	    transitionMatrixDefault = new double[][] { new double[] { zeta11, zeta12, 0.0 }, new double[] { zeta21, zeta22, zeta23 }, new double[] { 0.0, zeta32, zeta33 } };
	}
	
	private double[][] transitionMatrix;		// phi in [i][j] (i = row, j = column)

	public EvolutionOfCarbonConcentration(double timeStepSize, double[][] transitionMatrix) {
		super();
		this.timeStepSize = timeStepSize;
		this.transitionMatrix = transitionMatrix;
	}

	public EvolutionOfCarbonConcentration() {
 		// Parameters from original model
		this(5.0, transitionMatrixDefault);
	}

	@Override
	/**
	 * Update CarbonConcentration over one time step with a given emission.
	 * 
	 * @param carbonConcentration The CarbonConcentration in time \( t_{i} \)
	 * @param emissions The emissions in t CO2
	 */
	public CarbonConcentration apply(CarbonConcentration carbonConcentration, Double emissions) {
		double[] carbonConcentrationNext = LinearAlgebra.multMatrixVector(transitionMatrix, carbonConcentration.getAsDoubleArray());

		// Add emissions
		carbonConcentrationNext[0] += emissions * timeStepSize / 3.666;
		
		return new CarbonConcentration(carbonConcentrationNext);
	}
}
