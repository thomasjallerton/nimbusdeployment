package mojo;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import persisted.ExportInformation;
import persisted.FileUploadDescription;
import persisted.HandlerInformation;
import persisted.NimbusState;
import services.*;
import services.CloudFormationService.CreateStackResponse;
import services.CloudFormationService.FindExportResponse;

import java.net.URL;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static configuration.ConfigurationKt.*;

@Mojo(name = "deploy")
public class DeploymentMojo extends AbstractMojo {

    private Log logger;

    @Parameter(property = "region", defaultValue = "eu-west-1")
    private String region;

    @Parameter(property = "stage", defaultValue = "dev")
    private String stage;

    @Parameter(property = "shadedJarPath", defaultValue = "target/functions.jar")
    private String lambdaPath;

    @Parameter(property = "assembledJarsDirectory", defaultValue = "target/")
    private String assembledJarsDirectory;

    @Parameter(property = "compiledSourcePath", defaultValue = "target/generated-sources/annotations/")
    private String compiledSourcePath;

    public DeploymentMojo() {
        super();
        logger = getLog();
    }


    public void execute() throws MojoExecutionException, MojoFailureException {
        FileService fileService = new FileService(logger);

        String compiledSourcePathFixed = FileService.addDirectorySeparatorIfNecessary(compiledSourcePath);
        NimbusState nimbusState = new NimbusStateService(logger).getNimbusState(compiledSourcePathFixed);

        CloudFormationService cloudFormationService = new CloudFormationService(logger, region);
        S3Service s3Service = new S3Service(region, nimbusState, logger);

        String stackName = nimbusState.getProjectName() + "-" + stage;
        logger.info("Beginning deployment for project: " + nimbusState.getProjectName() + ", stage: " + stage);
        //Try to create stack
        CreateStackResponse createSuccessful = cloudFormationService.createStack(stackName, stage, compiledSourcePathFixed);
        if (!createSuccessful.getSuccessful()) throw new MojoFailureException("Unable to create stack");

        if (createSuccessful.getAlreadyExists()) {
            logger.info("Stack already exists, proceeding to update");
        } else {
            logger.info("Creating stack");
            logger.info("Polling stack create progress");
            cloudFormationService.pollStackStatus(stackName, 0);
            logger.info("Stack created");
        }


        FindExportResponse lambdaBucketName = cloudFormationService.findExport(
                nimbusState.getProjectName() + "-" + stage + "-" + DEPLOYMENT_BUCKET_NAME);

        if (!lambdaBucketName.getSuccessful()) throw new MojoFailureException("Unable to find deployment bucket");

        if (!nimbusState.getAssemble()) {
            logger.info("Uploading lambda file");
            boolean uploadSuccessful = s3Service.uploadShadedLambdaJarToS3(lambdaBucketName.getResult(), lambdaPath, "lambdacode");
            if (!uploadSuccessful) throw new MojoFailureException("Failed uploading lambda code");
        } else {
            int numberOfHandlers = nimbusState.getHandlerFiles().size();
            int count = 1;
            for (HandlerInformation handler : nimbusState.getHandlerFiles()) {
                logger.info("Uploading lambda handler " + count + "/" + numberOfHandlers);
                count++;
                String path = FileService.addDirectorySeparatorIfNecessary(assembledJarsDirectory) + handler.getHandlerFile();
                boolean uploadSuccessful = s3Service.uploadShadedLambdaJarToS3(lambdaBucketName.getResult(), path, handler.getHandlerFile());
                if (!uploadSuccessful) throw new MojoFailureException("Failed uploading lambda code, have you run the assemble goal?");
            }
        }


        logger.info("Uploading cloudformation file");
        boolean cloudFormationUploadSuccessful = s3Service.uploadShadedLambdaJarToS3(lambdaBucketName.getResult(), compiledSourcePathFixed + STACK_UPDATE_FILE + "-" + stage + ".json", "update-template");
        if (!cloudFormationUploadSuccessful) throw new MojoFailureException("Failed uploading cloudformation update code");

        URL cloudformationUrl = s3Service.getUrl(lambdaBucketName.getResult(), "update-template");

        boolean updating = cloudFormationService.updateStack(stackName, cloudformationUrl);

        if (!updating) throw new MojoFailureException("Unable to update stack");

        logger.info("Updating stack");

        cloudFormationService.pollStackStatus(stackName, 0);

        logger.info("Updated stack successfully, deployment complete");


        //Deal with substitutions
        Map<String, String> substitutionParams = new HashMap<>();
        Map<String, String> outputMessages = new HashMap<>();

        List<ExportInformation> exports = nimbusState.getExports().getOrDefault(stage, new LinkedList<>());
        for (ExportInformation export : exports) {
            FindExportResponse exportResponse = cloudFormationService.findExport(export.getExportName());
            if (exportResponse.getSuccessful()) {
                String result = exportResponse.getResult();
                substitutionParams.put(export.getSubstitutionVariable(), result);
                outputMessages.put(export.getExportMessage(), result);
            }
        }

        if (nimbusState.getFileUploads().size() > 0) {
            logger.info("Starting File Uploads");

            Map<String, List<FileUploadDescription>> bucketUploads = nimbusState.getFileUploads().get(stage);
            for (Map.Entry<String, List<FileUploadDescription>> bucketUpload : bucketUploads.entrySet()) {
                String bucketName = bucketUpload.getKey();
                for (FileUploadDescription fileUploadDescription: bucketUpload.getValue()) {
                    String localFile = fileUploadDescription.getLocalFile();
                    String targetFile = fileUploadDescription.getTargetFile();

                    if (fileUploadDescription.getSubstituteVariables()) {
                        s3Service.uploadToS3(bucketName, localFile, targetFile,
                                (file) -> fileService.replaceInFile(substitutionParams, file));
                    } else {
                        s3Service.uploadToS3(bucketName, localFile, targetFile, (file) -> file);
                    }
                }
            }
        }

        if (nimbusState.getAfterDeployments().size() > 0) {
            logger.info("Starting after deployment script");

            LambdaService lambdaClient = new LambdaService(logger, region);

            List<String> afterDeployments = nimbusState.getAfterDeployments().get(stage);
            if (afterDeployments != null) {
                for (String lambda : afterDeployments) {
                    lambdaClient.invokeNoArgs(lambda);
                }
            }
        }

        logger.info("Deployment completed");

        for (Map.Entry<String, String> entry : outputMessages.entrySet()) {
            logger.info(entry.getKey() + entry.getValue());
        }
    }
}
