// aws-eks.kuml.kts — UML 2.x deployment diagram (V1.1).
// A small AWS deployment: VPC + EKS cluster + Postgres + S3.

deploymentDiagram(name = "AWS — kUML.dev production") {
    val vpc = executionEnvironment(name = "VPC eu-central-1") {
        node(name = "EKS Cluster") {
            artifact(name = "kuml-web.jar")
            artifact(name = "kuml-mcp.jar")
        }
    }
    val db = device(name = "RDS PostgreSQL")
    val s3 = device(name = "S3 bucket")
    communicationPath(end1 = vpc, end2 = db)
    communicationPath(end1 = vpc, end2 = s3)
}
