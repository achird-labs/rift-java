package io.github.achirdlabs.rift.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the flow-state config predicates (#40). Defs are built via {@link
 * ImposterDefinition#fromJson} to exercise arbitrary shapes without going through the DSL's
 * build-time validation.
 */
class FlowStateSupportTest {

    private static ImposterDefinition def(String json) {
        return ImposterDefinition.fromJson(json);
    }

    // --- hasHeaderFlowIdSource ---

    @Test
    void headerFlowIdSourceDetected() {
        assertTrue(FlowStateSupport.hasHeaderFlowIdSource(def("""
                {"port":1,"protocol":"http","stubs":[],
                 "_rift":{"flowState":{"backend":"inmemory","flowIdSource":"header:X-Flow"}}}""")));
    }

    @Test
    void portFlowIdSourceIsNotHeader() {
        assertFalse(FlowStateSupport.hasHeaderFlowIdSource(def("""
                {"port":1,"protocol":"http","stubs":[],
                 "_rift":{"flowState":{"backend":"inmemory","flowIdSource":"imposter_port"}}}""")));
    }

    @Test
    void flowStateWithoutFlowIdSourceIsNotHeader() {
        assertFalse(FlowStateSupport.hasHeaderFlowIdSource(def("""
                {"port":1,"protocol":"http","stubs":[],"_rift":{"flowState":{"backend":"inmemory"}}}""")));
    }

    @Test
    void absentFlowStateIsNotHeaderSource() {
        assertFalse(FlowStateSupport.hasHeaderFlowIdSource(def("""
                {"port":1,"protocol":"http","stubs":[]}""")));
    }

    // --- hasSpaceStub ---

    @Test
    void spaceStubDetected() {
        assertTrue(FlowStateSupport.hasSpaceStub(def("""
                {"port":1,"protocol":"http","stubs":[
                  {"space":"alice","predicates":[],"responses":[{"is":{"statusCode":200}}]}]}""")));
    }

    @Test
    void noSpaceStub() {
        assertFalse(FlowStateSupport.hasSpaceStub(def("""
                {"port":1,"protocol":"http","stubs":[
                  {"predicates":[],"responses":[{"is":{"statusCode":200}}]}]}""")));
    }

    // --- hasStoreTrigger (engine auto-provisions a store for these; #514/#358) ---

    @Test
    void explicitFlowStateTriggersStore() {
        assertTrue(FlowStateSupport.hasStoreTrigger(def("""
                {"port":1,"protocol":"http","stubs":[],"_rift":{"flowState":{"backend":"inmemory"}}}""")));
    }

    @Test
    void scenarioNameOnlyTriggersStore() {
        assertTrue(FlowStateSupport.hasStoreTrigger(def("""
                {"port":1,"protocol":"http","stubs":[
                  {"scenarioName":"cart","predicates":[],"responses":[{"is":{"statusCode":200}}]}]}""")));
    }

    @Test
    void requiredScenarioStateTriggersStore() {
        assertTrue(FlowStateSupport.hasStoreTrigger(def("""
                {"port":1,"protocol":"http","stubs":[
                  {"requiredScenarioState":"empty","predicates":[],"responses":[{"is":{"statusCode":200}}]}]}""")));
    }

    @Test
    void newScenarioStateOnlyTriggersStore() {
        assertTrue(FlowStateSupport.hasStoreTrigger(def("""
                {"port":1,"protocol":"http","stubs":[
                  {"newScenarioState":"filled","predicates":[],"responses":[{"is":{"statusCode":200}}]}]}""")));
    }

    @Test
    void stateOpsOnAnIsResponseTriggerStore() {
        assertTrue(FlowStateSupport.hasStoreTrigger(def("""
                {"port":1,"protocol":"http","stubs":[
                  {"predicates":[],"responses":[{"is":{"statusCode":200},"_rift":{"stateOps":[{"op":"clearFlow"}]}}]}]}""")));
    }

    @Test
    void scriptOnlyResponseTriggersStore() {
        assertTrue(FlowStateSupport.hasStoreTrigger(def("""
                {"port":1,"protocol":"http","stubs":[
                  {"predicates":[],"responses":[{"_rift":{"script":{"engine":"rhai","code":"fn respond(ctx){http(200,#{})}"}}}]}]}""")));
    }

    @Test
    void isWithScriptExtensionTriggersStore() {
        assertTrue(FlowStateSupport.hasStoreTrigger(def("""
                {"port":1,"protocol":"http","stubs":[
                  {"predicates":[],"responses":[{"is":{"statusCode":200},"_rift":{"script":{"engine":"rhai","code":"x"}}}]}]}""")));
    }

    @Test
    void plainDefHasNoStoreTrigger() {
        assertFalse(FlowStateSupport.hasStoreTrigger(def("""
                {"port":1,"protocol":"http","stubs":[
                  {"predicates":[],"responses":[{"is":{"statusCode":200}}]}]}""")));
    }
}
