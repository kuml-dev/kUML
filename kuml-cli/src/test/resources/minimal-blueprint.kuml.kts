// Minimal Blueprint fixture for RenderPipelineTest (V3.1.24).
// Two phases, one step each — enough for the grid renderer to produce a valid SVG.
blueprint("Onboarding") {
    phase("Entdeckung") {
        customer("Sieht Post", Sentiment.NEUTRAL)
    }
    phase("Interesse") {
        customer("Liest Programm", Sentiment.POSITIVE)
    }
    journeyDiagram("Journey")
}
