package edu.cuny.qc.perceptron.types;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.Vector;

import org.apache.commons.lang3.StringUtils;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;

import edu.cuny.qc.ace.acetypes.AceEventMention;
import edu.cuny.qc.ace.acetypes.AceEventMentionArgument;
import edu.cuny.qc.ace.acetypes.AceMention;
import edu.cuny.qc.perceptron.core.Controller;
import edu.cuny.qc.perceptron.core.Perceptron;
import edu.cuny.qc.perceptron.featureGenerator.EdgeFeatureGenerator;
import edu.cuny.qc.perceptron.featureGenerator.GlobalFeatureGenerator;
import edu.cuny.qc.perceptron.types.SentenceInstance.InstanceAnnotations;
import edu.cuny.qc.util.FinalKeysMap;
import edu.cuny.qc.util.TypeConstraints;
import edu.cuny.qc.util.UnsupportedParameterException;

/**
 * For the (target) assignment, it should encode two types of assignment:
 * (1) label assignment for each token: refers to the event trigger classification 
 * (2) assignment for any sub-structure of the sentence, e.g. one assignment indicats that 
 * the second token is argument of the first trigger
 * in the simplest case, this type of assignment involves two tokens in the sentence
 * 
 * 
 * @author che
 *
 */
public class SentenceAssignment
{
	public static final String PAD_Trigger_Label = "O\t"; // pad for the intial state
	public static final String Default_Trigger_Label = PAD_Trigger_Label;
	public static final String Default_Argument_Label = "X\t";
	public static final String Really_Generic_Existing_Label = "LBL";
	public static final String GLOBAL_LABEL = "GLOBAL";
	
	public static final String CURRENT_LABEL_MARKER = "\tcurrentLabel:";
	public static final String TRIGGER_LABEL_MARKER = "triggerLabel:";
	public static final String ARG_ROLE_MARKER = "\tArgRole:";
	public static final String IS_ARG_MARKER = "IsArg";
	public static final List<String> ALL_FEATURE_NAME_MARKERS = ImmutableList.of(CURRENT_LABEL_MARKER, TRIGGER_LABEL_MARKER, ARG_ROLE_MARKER, IS_ARG_MARKER);

	// {signalName : {label : {moreParams : featureName}}}
	public static Map<String, Map<String, Map<String, String>>> signalToFeature = Maps.newTreeMap();
	public static List<String> storedLabels;
	public static int numStoredTriggerLabels = 0;
	public static int numStoredArgLabels = 0;
	public static final int SIGNALS_TO_STORE = 100;
	public static final int TRIGGER_LABELS_TO_STORE = 3;
	public static final int ARGUMENT_LABELS_TO_STORE = 3;
	/**
	 * the index of last processed (assigned/searched) token
	 */
	int state = -1;
	
	public static String getReallyGenericLabel(String label) {
		if (label.equals(Default_Trigger_Label) || label.equals(Default_Argument_Label)) {
			return Default_Trigger_Label.trim();
		}
		else {
			return Really_Generic_Existing_Label;
		}
	}
	
	public static String stripLabel(String featureName) {
		String result = featureName;
		for (String marker : ALL_FEATURE_NAME_MARKERS) {
			result = result.replaceFirst(String.format("%s\\S+",  marker), "");
		}
		return result;
	}
	
	public void retSetState()
	{
		state = -1;
	}
	
	public int getState()
	{
		return state;
	}
	
	/*
	 * indicates if it violates gold-standard, useful for learning
	 */
	boolean violate = false;
	
	// the alphabet of the label for each node (token), shared by the whole application
	// they should be consistent with SentenceInstance object
	public Alphabet nodeTargetAlphabet;
	
	// the alphabet of the label for each edge (trigger-->argument link), shared by the whole application
	// they should be consistent with SentenceInstance object
	public Alphabet edgeTargetAlphabet;

	// the alphabet of features, shared by the whole application
	public Alphabet featureAlphabet;
	
	public Controller controller;
	
	// the feature vector of the current assignment
	public FeatureVectorSequence featVecSequence;
	
	// the score of the assignment, it can be partial score when the assignment is not complete
	protected double score = 0.0;
	protected List<Double> partial_scores;
	
	public List<Double> getPartialScores() {
		return partial_scores;
	}
	
	public FeatureVector getFV(int index)
	{
		return featVecSequence.get(index);
	}
	
	public FeatureVector getCurrentFV()
	{
		return featVecSequence.get(state);
	}
	
	public FeatureVectorSequence getFeatureVectorSequence()
	{
		return featVecSequence;
	}
	
	public void addFeatureVector(FeatureVector fv)
	{
		featVecSequence.add(fv);
	}
	
	/**
	 * as the search processed, increament the state for next token
	 * creat a new featurevector for this state
	 */
	public void incrementState()
	{
		state++;
		FeatureVector fv = new FeatureVector();
		this.addFeatureVector(fv);
		this.partial_scores.add(0.0);
	}
	
	/**
	 * assignment to each node, node-->assignment
	 */
	protected Vector<Integer> nodeAssignment;
	
	public Vector<Integer> getNodeAssignment()
	{
		return nodeAssignment;
	}
	
	/**
	 * assignment to each argument edge trigger-->arg --> assignment
	 */
	protected Map<Integer, Map<Integer, Integer>> edgeAssignment;
	
	/**
	 * deep copy an assignment
	 */
	public SentenceAssignment clone()
	{
		// shallow copy the alphabets
		SentenceAssignment assn = new SentenceAssignment(nodeTargetAlphabet, edgeTargetAlphabet, featureAlphabet, controller);
		
		// shallow copy the assignment
		assn.nodeAssignment = (Vector<Integer>) this.nodeAssignment.clone();
		// deep copy the edge assignment for the last element 
		// (this is because in the beam search, we need expand the last statement for arguments labeling)
		for(Integer key : this.edgeAssignment.keySet())
		{
			Map<Integer, Integer> edgeMap = this.edgeAssignment.get(key);
			if(edgeMap != null)
			{
				if(key < this.getState())
				{
					assn.edgeAssignment.put(key, edgeMap);
				}
				else // deep copy the last element
				{
					Map<Integer, Integer> new_edgeMap = new HashMap<Integer, Integer>();
					new_edgeMap.putAll(edgeMap);
					assn.edgeAssignment.put(key, new_edgeMap);
				}
			}
		}
		
		// deep copy the feature vector sequence for the last element
		assn.featVecSequence = this.featVecSequence.clone2();
		
		// deep copy attributes
		assn.state = this.state;
		assn.score = this.score;
		//assn.local_score = this.local_score;
		assn.partial_scores.addAll(this.partial_scores);
		
		return assn;
	}
	
	public Map<Integer, Map<Integer, Integer>> getEdgeAssignment()
	{
		return edgeAssignment;
	}
	
	/**
	 * get the node label of the current node
	 * @return
	 */
	public String getLabelAtToken(int i)
	{
		assert(i <= state);
		
		String label;
		if(i >= 0 )
		{
			label = (String) nodeTargetAlphabet.lookupObject(nodeAssignment.get(i));
		}
		else
		{
			label = PAD_Trigger_Label;
		}
		return label;
	}
	
	/**
	 * get the node label of the current node
	 * @return
	 */
	public String getCurrentNodeLabel()
	{
		String label;
		if(state >= 0 )
		{
			label = (String) nodeTargetAlphabet.lookupObject(nodeAssignment.get(state));
		}
		else
		{
			label = PAD_Trigger_Label;
		}
		return label;
	}
	
	/**
	 * set current node label
	 * @return
	 */
	public void setCurrentNodeLabel(int index)
	{
		if(state >= 0 )
		{
			if(nodeAssignment.size() <= state)
			{
				nodeAssignment.setSize(state + 1);
			}
			this.nodeAssignment.set(state, index);
		}
	}
	
	/**
	 * set current node label
	 * @return
	 */
	public void setCurrentNodeLabel(String label)
	{
		if(state >= 0 )
		{
			int index = nodeTargetAlphabet.lookupIndex(label);
			if(nodeAssignment.size() <= state)
			{
				nodeAssignment.setSize(state + 1);
			}
			this.nodeAssignment.set(state, index);
		}
	}
	
	public Map<Integer, Integer> getCurrentEdgeLabels()
	{
		Map<Integer, Integer> map = this.edgeAssignment.get(state);
		if(map == null)
		{
			map = new HashMap<Integer, Integer>();
			this.edgeAssignment.put(state, map);
		}
		return map;
	}
	
	/**
	 * set current node edge
	 * @return
	 */
	public void setCurrentEdgeLabel(int arg_idx, String label)
	{
		if(state >= 0 )
		{
			int index = edgeTargetAlphabet.lookupIndex(label);
			Map<Integer, Integer> map = this.edgeAssignment.get(state);
			if(map == null)
			{
				map = new HashMap<Integer, Integer>();
			}
			map.put(arg_idx, index);
			this.edgeAssignment.put(state, map);
		}
	}
	
	/**
	 * set current node edges
	 * @return
	 */
	public void setCurrentEdges(Map<Integer, Integer> edges)
	{
		if(state >= 0 )
		{
			Map<Integer, Integer> map = this.edgeAssignment.get(state);
			if(map == null)
			{
				map = new HashMap<Integer, Integer>();
			}
			map.putAll(edges);
			this.edgeAssignment.put(state, map);
		}
	}
	
	/**
	 * set current node label
	 * @return
	 */
	public void setCurrentEdgeLabel(int arg_idx, int label)
	{
		if(state >= 0 )
		{
			Map<Integer, Integer> map = this.edgeAssignment.get(state);
			if(map == null)
			{
				map = new HashMap<Integer, Integer>();
			}
			map.put(arg_idx, label);
			this.edgeAssignment.put(state, map);
		}
	}
	
	public SentenceAssignment(Alphabet nodeTargetAlphabet, Alphabet edgeTargetAlphabet, Alphabet featureAlphabet, Controller controller)
	{
		this.nodeTargetAlphabet = nodeTargetAlphabet;
		this.edgeTargetAlphabet = edgeTargetAlphabet;
		this.featureAlphabet = featureAlphabet;
		this.controller = controller;
		
		nodeAssignment = new Vector<Integer>();
		edgeAssignment = new HashMap<Integer, Map<Integer, Integer>>();
		
		featVecSequence = new FeatureVectorSequence();
		partial_scores = new ArrayList<Double>();
	}
	
	/**
	 * given an labeled instance, create a target assignment as ground-truth
	 * also create a full featureVectorSequence
	 * @param inst
	 */
	public SentenceAssignment(SentenceInstance inst)
	{
		this(inst.nodeTargetAlphabet, inst.edgeTargetAlphabet, inst.featureAlphabet, inst.controller);
		
		for(int i=0; i < inst.size(); i++)
		{
			int label_index = this.nodeTargetAlphabet.lookupIndex(Default_Trigger_Label);
			this.nodeAssignment.add(label_index);
			this.incrementState();
		}
		
		// use sentence data to assign event/arg tags
		for(AceEventMention mention : inst.eventMentions)
		{
			Vector<Integer> headIndices = mention.getHeadIndices();
			
			// for event, only pick up the first token as trigger
			int trigger_index = headIndices.get(0);  
			// ignore the triggers that are with other POS
			if(!TypeConstraints.isPossibleTriggerByPOS(inst, trigger_index))
			{	
				continue;
			}
			int feat_index = this.nodeTargetAlphabet.lookupIndex(mention.getSubType());
			this.nodeAssignment.set(trigger_index, feat_index);
			
			Map<Integer, Integer> arguments = edgeAssignment.get(trigger_index);
			if(arguments == null)
			{
				arguments = new HashMap<Integer, Integer>();
				edgeAssignment.put(trigger_index, arguments);
			}
			
			// set default edge label between each trigger-entity pair
			for(int can_id=0; can_id < inst.eventArgCandidates.size(); can_id++)
			{
				AceMention can = inst.eventArgCandidates.get(can_id);
				// ignore entity that are not compatible with the event
				if(TypeConstraints.isEntityTypeEventCompatible(mention.getSubType(), can.getType()))
				{
					feat_index = this.edgeTargetAlphabet.lookupIndex(Default_Argument_Label);
					arguments.put(can_id, feat_index);
				}
			}
			
			// set argument role labels
			for(AceEventMentionArgument arg : mention.arguments)
			{
				AceMention arg_mention = arg.value;
				int arg_index = inst.eventArgCandidates.indexOf(arg_mention);
				feat_index = this.edgeTargetAlphabet.lookupIndex(arg.role);
				arguments.put(arg_index, feat_index);
			}
		}
		
		// create featureVectorSequence
		for(int i=0; i<=state; i++)
		{
			makeAllFeatureForSingleState(inst, i, inst.learnable, inst.learnable);
		}
	}
	
	private void makeAllFeatureForSingleState(SentenceInstance problem, int i, boolean addIfNotPresent,
			boolean useIfNotPresent)
	{
		// make basic bigram features for event trigger
		this.makeNodeFeatures(problem, i, addIfNotPresent, useIfNotPresent);
		// make basic features of the argument-trigger link
		this.makeEdgeFeatures(problem, i, addIfNotPresent, useIfNotPresent);
		if(problem.controller.useGlobalFeature)
		{
			// make various global features
			this.makeGlobalFeatures(problem, i, addIfNotPresent, useIfNotPresent);
		}
	}

	/**
	 * extract global features from the current assignment
	 * @param assn
	 * @param problem
	 */
	public void makeEdgeFeatures(SentenceInstance problem, int index, boolean addIfNotPresent, 
			boolean useIfNotPresent)
	{
		// node label
		String nodeLabel = this.getLabelAtToken(index);
		if(!isArgumentable(nodeLabel))
		{
			return;
		}
		
		Map<Integer, Integer> edge = edgeAssignment.get(index);
		if(edge == null)
		{
			return;
		}
		
		for(Integer key : edge.keySet())
		{
			// edge label
			makeEdgeLocalFeature(problem, index, addIfNotPresent, key, useIfNotPresent);
		}
	}

	public void makeEdgeLocalFeature(SentenceInstance problem, int index, boolean addIfNotPresent, 
			int entityIndex, boolean useIfNotPresent)
	{	
		if (!controller.useArguments) {
			return;
		}
		
		if(this.edgeAssignment.get(index) == null)
		{
			// skip assignments that don't have edgeAssignment for index-th node
			return;
		}
		Integer edgeLabelIndx = this.edgeAssignment.get(index).get(entityIndex);
		if(edgeLabelIndx == null)
		{
			return;
		}
		String edgeLabel = (String) this.edgeTargetAlphabet.lookupObject(edgeLabelIndx);
		// if the argument role is NON, then do not produce any feature for it
		if(controller.skipNonArgument && edgeLabel.equals(SentenceAssignment.Default_Argument_Label))
		{
			return; 
		}
		
		List<List<List<String>>> edgeFeatVectors = (List<List<List<String>>>) problem.get(InstanceAnnotations.EdgeTextFeatureVectors);
		List<String> textFeatures = edgeFeatVectors.get(index).get(entityIndex);
		AceMention mention = problem.eventArgCandidates.get(entityIndex);
		
		int nodeLabelIndex = this.nodeAssignment.get(index);
		String nodeLabel = (String) this.nodeTargetAlphabet.lookupObject(nodeLabelIndex);
		FeatureVector fv = this.getFeatureVectorSequence().get(index);
		if(textFeatures == null)
		{
			textFeatures = EdgeFeatureGenerator.get_edge_text_features(problem, index, mention);
			edgeFeatVectors.get(index).set(entityIndex, textFeatures);
		}
		for(String textFeature : textFeatures)
		{
			String featureStr = "";
			String signalStr = "";
		
			if(!edgeLabel.equals(SentenceAssignment.Default_Argument_Label))
			{
				if(!TypeConstraints.isIndependentRole(edgeLabel))
				{
					// if the role is dependent on event type, then creat feature based on nodeLabel + edge label
					signalStr = "EdgeLocalFeature:\t" + textFeature;
					featureStr = signalStr + "\t" + TRIGGER_LABEL_MARKER + nodeLabel + ARG_ROLE_MARKER + edgeLabel;
					makeFeature(featureStr, fv, signalStr, edgeLabel, null, addIfNotPresent, useIfNotPresent);
				}
				else
				{
					// if the role (like Place or Timex) is independent on event type, then creat feature only based on edge label
					signalStr = "EdgeLocalFeature:\t" + textFeature;
					featureStr = signalStr + "\t" + ARG_ROLE_MARKER + edgeLabel;
					makeFeature(featureStr, fv, signalStr, edgeLabel, null, addIfNotPresent, useIfNotPresent);
				}
			
				// add backoff feature for argument
				signalStr = "EdgeLocalFeature:\t" + textFeature;
				featureStr = signalStr + "\t" + IS_ARG_MARKER;
				makeFeature(featureStr, fv, signalStr, "ARG", null, addIfNotPresent, useIfNotPresent);
			}
			else
			{
				// feature for NON
				signalStr = "EdgeLocalFeature:\t" + textFeature;
				featureStr = signalStr + "\t" + edgeLabel;
				makeFeature(featureStr, fv, signalStr, edgeLabel, null, addIfNotPresent, useIfNotPresent);
			}
		}
		String signalStr = "EdgeLocalFeature:\tTriggerType=" + nodeLabel;
		String featureStr = signalStr + "\t" + ARG_ROLE_MARKER + edgeLabel;
		makeFeature(featureStr, fv, signalStr, edgeLabel, null, addIfNotPresent, useIfNotPresent);
	}
	
	/**
	 * This type of feature applies in each step of trigger classification
	 * @param problem
	 * @param index
	 * @param addIfNotPresent
	 * @param useIfNotPresent
	 */
	public void makeGlobalFeaturesTrigger(SentenceInstance problem, int index, boolean addIfNotPresent,
			boolean useIfNotPresent)
	{
		FeatureVector fv = this.getFV(index);
		List<String> featureStrs = GlobalFeatureGenerator.get_global_features_triggers(problem, index, this);
		for(String feature : featureStrs)
		{
			String featureStr = "TriggerLevelGlobalFeature:\t" + feature;
			makeFeature(featureStr, fv, featureStr, GLOBAL_LABEL, null, addIfNotPresent, useIfNotPresent);
		}
	} 
	
	/**
	 * this is global feature applicable when arugment searching in a node is complete
	 * @param problem
	 * @param index
	 * @param addIfNotPresent
	 */
	public void makeGlobalFeaturesComplete(SentenceInstance problem, int index, boolean addIfNotPresent,
			boolean useIfNotPresent)
	{
		FeatureVector fv = this.getFV(index);
		List<String> featureStrs = GlobalFeatureGenerator.get_global_features_node_level_omplete(problem, index, this);
		for(String feature : featureStrs)
		{
			String featureStr = "NodeLevelGlobalFeature:\t" + feature;
			makeFeature(featureStr, fv, featureStr, GLOBAL_LABEL, null, addIfNotPresent, useIfNotPresent);
		}
	}
	
	/**
	 * this version of global features are added when each step of argument searching
	 * @param problem
	 * @param index
	 * @param entityIndex
	 * @param addIfNotPresent
	 */
	public void makeGlobalFeaturesProgress(SentenceInstance problem, int index, int entityIndex, boolean addIfNotPresent,
			boolean useIfNotPresent)
	{
		FeatureVector fv = this.getFV(index);
		List<String> featureStrs = GlobalFeatureGenerator.get_global_features_node_level(problem, index, this, entityIndex);
		for(String feature : featureStrs)
		{
			String featureStr = "NodeLevelGlobalFeature:\t" + feature;
			makeFeature(featureStr, fv, featureStr, GLOBAL_LABEL, null, addIfNotPresent, useIfNotPresent);
		}
		featureStrs = GlobalFeatureGenerator.get_global_features_sent_level(problem, index, this, entityIndex);
		for(String feature : featureStrs)
		{
			String featureStr = "SentLevelGlobalFeature:\t" + feature;
			makeFeature(featureStr, fv, featureStr, GLOBAL_LABEL, null, addIfNotPresent, useIfNotPresent);
		}
		
	}
	
	/**
	 * this version of global features are added when argument searching for each node is completed 
	 * assume the argument search in token i is finished, then fill-in global features in token i
	 * @param problem
	 * @param i
	 * @param addIfNotPresent
	 */
	public void makeGlobalFeatures(SentenceInstance problem, int index, boolean addIfNotPresent, 
			boolean useIfNotPresent)
	{
		if(this.edgeAssignment.get(index) == null)
		{
			return;
		}
		makeGlobalFeaturesComplete(problem, index, addIfNotPresent, useIfNotPresent);
		for(int entityIndex : edgeAssignment.keySet())
		{
			makeGlobalFeaturesProgress(problem, index, entityIndex, addIfNotPresent, useIfNotPresent);
		}
	}
	
	public void makeNodeFeatures(SentenceInstance problem, int i, boolean addIfNotPresent, boolean useIfNotPresent)
	{
		// make node feature (bigram feature)
		List<String> token = ((List<List<String>>) problem.get(InstanceAnnotations.NodeTextFeatureVectors)).get(i);
		String previousLabel = this.getLabelAtToken(i-1);
		String outcome = this.getLabelAtToken(i);
		//String reallyGenericLabel = getReallyGenericLabel(outcome);
		
		/// DEBUG
//		System.err.printf("\nmakeNodeFeatures, i=%s, inst=%s, token=%s:\n", i, problem, token);
//		if (problem.sentID==40) {
//			//System.err.printf("\n\nDebug point!!!\n\n");
//		}
		///

		// traverse each text feature of the token to explore the bigram featurs
		for(String textFeature : token)
		{
			if(this.controller.order >= 1)
			{
				throw new UnsupportedParameterException("order >= 1");
			}
			else // order = 0
			{
				// unigram features, for history reason, we still call them BigramFeature
				// create a bigram feature
				String signalStr = "BigramFeature:\t" + textFeature;
				String featureStr = signalStr + "\t" + CURRENT_LABEL_MARKER + outcome;
				makeFeature(featureStr, this.getFV(i), signalStr, outcome, null, addIfNotPresent, useIfNotPresent);
				// this is a backoff feature for event, use super event type/ except Transport, since Movement only have one subtype
				if(!outcome.equals(Default_Trigger_Label) && !outcome.equals("Transport"))
				{
					String superType = TypeConstraints.getEventSuperType(outcome);
					featureStr = signalStr + "\t" + CURRENT_LABEL_MARKER + superType;
					makeFeature(featureStr, this.getFV(i), signalStr, superType, null, addIfNotPresent, useIfNotPresent);
				}
			}
		}
		
		// if the previous label is a trigger, then get the bigram labels
		if(!previousLabel.equals(SentenceAssignment.Default_Trigger_Label))
		{
			String signalStr = "BigramFeature:\t" + "PreLabel:" + previousLabel;
			String featureStr = signalStr + CURRENT_LABEL_MARKER + outcome;
			makeFeature(featureStr, this.getFV(i), signalStr, outcome, null, addIfNotPresent, useIfNotPresent);
		}
	}
	
	/**
	 * add a (possible) feature to feature vector 
	 * @param featureStr
	 * @param fv
	 * @param add_if_not_present true if the feature is not in featureAlphabet, add it
	 * @param use_if_not_present true if the feature is not in featureAlphaebt, still use it in FV
	 */
	protected void makeFeature(String featureStr, FeatureVector fv,
			String signalName, String label, String moreParams,
			boolean add_if_not_present, boolean use_if_not_present)
	{
		// Feature feat = new Feature(null, featureStr);
		// lookup the feature table to create an assignment with the new feature
		boolean wasAdded = false;
		if(!use_if_not_present || add_if_not_present)
		{
			int feat_index = lookupFeatures(this.featureAlphabet, featureStr, add_if_not_present);
			if(feat_index != -1)
			{
				fv.add(featureStr, 1.0);
				wasAdded = true;
			}
		}
		else
		{
			fv.add(featureStr, 1.0);
			wasAdded = true;
		}
		
		if (wasAdded) {
			boolean shouldSaveSignal= false;
			String signalNameNormalized = Perceptron.feature(signalName).intern();
			Set<String> storedSignals = signalToFeature.keySet();
			
			// This entire block is good for limiting not just the amount of the signals, btu also the amount of the labels
			// Currently, I'm not doing that, so this is commented out
			
//			if (!storedSignals.contains(signalNameNormalized) && storedSignals.size()>=SIGNALS_TO_STORE) {
//				// do nothing, not storing signals
//			}
//			else if (storedLabels.contains(label)) {
//				shouldSaveSignal = true;
//			}
//			else if (signalNameNormalized.startsWith("B")) {
//				if (numStoredTriggerLabels < TRIGGER_LABELS_TO_STORE) {
//					storedLabels.add(label);
//					numStoredTriggerLabels++;
//					shouldSaveSignal = true;
//				}
//			}
//			else if (signalNameNormalized.startsWith("E")) {
//				if (numStoredArgLabels < ARGUMENT_LABELS_TO_STORE) {
//					storedLabels.add(label);
//					numStoredArgLabels++;
//					shouldSaveSignal = true;
//				}
//			}
//			else {
//				shouldSaveSignal = true;
//			}

			if (storedSignals.contains(signalNameNormalized) || storedSignals.size()<SIGNALS_TO_STORE) {
				shouldSaveSignal = true;
			}
			
			if (shouldSaveSignal) {
				Map<String, Map<String, String>> forSignal = signalToFeature.get(signalNameNormalized);
				if (forSignal == null) {
					forSignal = new HashMap<String, Map<String, String>>();
					signalToFeature.put(signalNameNormalized, forSignal);
				}
				//Entry<String, String> forCategory = new AbstractMap.SimpleEntry<String, String>(featureStr, label);
				Map<String, String> forLabel = forSignal.get(label);
				if (forLabel == null) {
					forLabel = new HashMap<String, String>();
					forSignal.put(label, forLabel);
				}
				
				forLabel.put(moreParams, featureStr);
				
				/// DEBUG
				//System.err.printf("  %s, sig=%s, label=%s, feat=%s.\n", state, signalName, label, featureStr);
				///
			}
		}
	}
	
	/**
	 * lookup the feature (new feature), and then add to alphabet / weights vector if needed
	 * @param assn
	 * @param feat
	 * @return
	 */
	protected static int lookupFeatures(Alphabet dict, Object feat, boolean add_if_not_present)
	{
		int feat_index = dict.lookupIndex(feat, add_if_not_present);
		
		if(feat_index == -1 && !add_if_not_present)
		{
			return -1;
		}
		
		return feat_index;
	}
	
	public void setScore(double sc)
	{
		score = sc;
	}
	
	/**
	 * get the score according to feature and weight 
	 * @return
	 */
	public double getScore()
	{
		return score;
	}
	
	/**
	 * assume a new state(token) is added during search, then calculate the score for this state, update the total score 
	 * and then, add it to the total score
	 */
	public void updateScoreForNewState(FeatureVector weights)
	{
		FeatureVector fv = this.getCurrentFV();
		double partial_score = fv.dotProduct(weights);
		this.partial_scores.set(state, partial_score);
		
		this.score = 0;
		for(int i=0; i<=state; i++)
		{
			this.score += this.partial_scores.get(i);
		}
	}

	/**
	 * check if two assignments equals to each other before (inclusive) state step
	 * @param target
	 * @param state2
	 */
	public boolean equals(SentenceAssignment assn, int step)
	{
		for(int i=0; i<=step && i<=assn.state && i<=this.state; i++)
		{
			if(!assn.nodeAssignment.get(i).equals(this.nodeAssignment.get(i)))
			{
				return false;
			}
			if(assn.edgeAssignment.get(i) != null && !assn.edgeAssignment.get(i).equals(this.edgeAssignment.get(i)))
			{
				return false;
			}
			// Qi: added April 11th, 2013
			if(assn.edgeAssignment.get(i) == null && this.edgeAssignment.get(i) != null)
			{
				System.err.println("SentenceAssignment:" + 721);
				return false;
			}
		}
		return true;
	}
	
	/**
	 * check if two assignments equals to each other 
	 * @param target
	 * @param state2
	 */
	@Override
	public boolean equals(Object obj)
	{
		if(!(obj instanceof SentenceAssignment))
		{
			return false;
		}
		SentenceAssignment assn = (SentenceAssignment) obj;
		if(this.state != assn.state)
		{
			return false;
		}
		for(int i=0; i<=assn.state && i<=this.state; i++)
		{
			if(!assn.nodeAssignment.get(i).equals(this.nodeAssignment.get(i)))
			{
				return false;
			}
			if(assn.edgeAssignment.get(i) != null && !assn.edgeAssignment.get(i).equals(this.edgeAssignment.get(i)))
			{
				return false;
			}
		}
		return true;
	}
	
	/**
	 * check if two assignments equals to each other before (inclusive) state step
	 * @param target
	 * @param state2
	 */
	public boolean equals(SentenceAssignment assn, int step, int argNum)
	{
		for(int i=0; i<step && i<=assn.state && i<=this.state; i++)
		{
			if(!assn.nodeAssignment.get(i).equals(this.nodeAssignment.get(i)))
			{
				return false;
			}
			if(assn.edgeAssignment.get(i) != null && !assn.edgeAssignment.get(i).equals(this.edgeAssignment.get(i)))
			{
				return false;
			}
			// Qi: added April 11th, 2013
			if(assn.edgeAssignment.get(i) == null && this.edgeAssignment.get(i) != null)
			{
				System.err.println("SentenceAssignment:" + 779);
				return false;
			}
		}
		if(!assn.nodeAssignment.get(step).equals(this.nodeAssignment.get(step)))
		{
			return false;
		}
		if(argNum < 0)
		{
			// if argument num < 0, only consider the trigger labeling
			return true;
		}
		else
		{
			Map<Integer, Integer> map_assn = assn.edgeAssignment.get(step);
			Map<Integer, Integer> map = this.edgeAssignment.get(step);
			if(map_assn == null && map == null)
			{
				return true;
			}
			for(int k=0; k<=argNum; k++)
			{
				Integer label_assn = null;
				Integer label = null;
				if(map_assn != null)
				{
					label_assn = map_assn.get(k);
				}
				if(map != null)
				{
					label = map.get(k);
				}
				if(label == null && label_assn != null || label != null && label_assn == null)
				{
					return false;
				}
				if(label != null && label_assn != null && (!label.equals(label_assn)))
				{
					return false;
				}
			}
		}
		return true;
	}
	
	public void setViolate(boolean violate)
	{
		this.violate = violate;
	}
	
	public boolean getViolate()
	{
		return this.violate;
	}
	
	@Override
	public String toString() {
		return toString(null);
	}
	
	public String toString(Integer maxNodes)
	{
		StringBuffer ret = new StringBuffer();;
		int i=0;
		for(Integer assn : this.nodeAssignment)
		{
			if (maxNodes == null || i < maxNodes) {
				String token_label = (String) this.nodeTargetAlphabet.lookupObject(assn);
				ret.append(" ");
				ret.append(token_label);
				
				Map<Integer, Integer> edges = this.edgeAssignment.get(i);
				if(edges != null)
				{
					ret.append("(");
					for(Integer key : edges.keySet())
					{
						ret.append(" ");
						ret.append(key);
						ret.append(":");
						Integer val = edges.get(key);
						String arg_role = (String) this.edgeTargetAlphabet.lookupObject(val);
						ret.append(arg_role);
					}
					ret.append(")");
				}
				i++;
			}
			else {
				ret.append("+");
				break;
			}
		}
		return ret.toString();
	}
	
	/**
	 * check if the label of the token is an event, thereby can be attached to argument
	 * @param currentNodeLabel
	 * @return
	 */
	public static boolean isArgumentable(String label)
	{
		if(label.equalsIgnoreCase(PAD_Trigger_Label))
		{
			return false;
		}
		return true;
	}

	// to store temprary local score
	//protected double local_score = 0.0;
	
//	public void setLocalScore(double local_score)
//	{
//		this.local_score = local_score;
//	}
	
//	public double getLocalScore()
//	{
//		return local_score;
//	}

//	public void addLocalScore(double local_score)
//	{
//		this.local_score += local_score;
//	}
	
	/**
	 * clear feature vectors, so that the Target assignment can creates its feature vector in beamSearch
	 */
	public void clearFeatureVectors()
	{
		for(int i=0; i<this.featVecSequence.size(); i++)
		{
			this.featVecSequence.sequence.set(i, new FeatureVector());
		}
	}
}
