# AI Lecture Assistant - Server-Side Prototype (AWS Lambda)

This repository contains the server-side source code for the AI Lecture Assistant project. This backend is built using a serverless architecture on AWS and serves as the engine for a proof-of-concept application designed to help students by generating notes and answering questions from lecture transcripts.

## Overview

The AI Lecture Assistant is a tool that leverages Large Language Models (LLMs) to make studying more efficient. Users can upload lecture transcripts, and the system will automatically:

1. Process and store the lecture content.

2. Generate concise, easy-to-read notes using Amazon Bedrock.

3. Enable students to ask questions about the lecture material through a Q&A interface, powered by a Retrieval-Augmented Generation (RAG) pipeline.

This backend is designed to be scalable, event-driven, and cost-effective by using AWS Lambda and other managed AWS services.

## Architecture

The application follows a microservices-based, serverless architecture. A React frontend interacts with the backend services through Amazon API Gateway, which routes requests to the appropriate AWS Lambda functions.

### Workflow Breakdown

1. **Upload Workflow:**

   * A user uploads a lecture transcript via the React frontend.

   * API Gateway triggers the **Upload Lambda** function.

   * The Lambda function saves the raw transcript to an **S3 Bucket**.

   * Lecture metadata (e.g., title, date, S3 location) is stored in an **Amazon RDS (MySQL)** database.

   * An **SNS Topic** receives a notification to kick off the processing workflow asynchronously.

2. **RAG / Processing:**

   * The SNS notification triggers the **Notes Lambda (RAG Engine)**.

   * This function retrieves the transcript from S3.

   * It uses **Amazon Bedrock (Llama 3 70B)** to generate notes from the transcript.

   * The generated notes are stored in a separate **S3 Bucket**.

   * The transcript content is chunked, converted into vector embeddings, and stored in a **FAISS Vector Database** to be used for Q&A.

3. **Student Interaction:**

   * **List Lectures:** The frontend calls the **List Lectures Lambda** via API Gateway to fetch and display a list of all available lectures from the RDS database.

   * **Q&A:** When a student asks a question, the request is sent to the **FAQ Lambda (RAG/QA)**. This function converts the question into an embedding, queries the FAISS Vector DB to find the most relevant context from the lecture, and then uses Amazon Bedrock to generate a precise answer.

## ✨ Features

* **Serverless & Scalable:** Built with AWS Lambda to handle requests without managing servers.

* **Asynchronous Processing:** Uses S3 events and SNS to process transcripts without blocking the user interface.

* **AI-Powered Note Generation:** Leverages Amazon Bedrock and Llama 3 for high-quality, automated note creation.

* **Retrieval-Augmented Generation (RAG):** Provides an intelligent Q&A system that answers questions based on the specific content of the uploaded lectures.

* **Centralized Metadata Storage:** Uses Amazon RDS to manage lecture information efficiently.

## 📁 Code Structure

The repository is organized into the main Java source for Lambda handlers and a separate directory for the Python-based chat Lambda.

```

.
├── chat-lambda-py/               \# Python source for the FAQ/Chat Lambda
│   └── ...
├── src/main/java/com/genai/
│   ├── handler/                  \# Core AWS Lambda handlers
│   │   ├── UploadLambdaHandler.java
│   │   ├── ListLecturesHandler.java
│   │   └── NotesGenerationLambdaHandler.java
│   └── util/
│       └── DBUtil.java           \# Utility for handling RDS database connections
├── .gitignore
├── pom.xml                       \# Maven dependencies and build configuration
├── template.yaml                 \# AWS SAM template for deployment (inferred)
└── ...                           \# Other configuration files (e.g., CloudFormation)

```

### Key Components

* **`handler/`**: Contains the main business logic for each Lambda function.

* **`DBUtil.java`**: A helper class that reads database credentials from Lambda environment variables to establish a connection with the MySQL database.

* **`pom.xml`**: Defines all the necessary Java dependencies, such as the AWS SDK for Java and the MySQL connector.

* **`chat-lambda-py/`**: Houses the Python code for the user-facing Q&A functionality, which interacts with the vector database and Bedrock.

## 🛠️ Deployment

This project is intended to be deployed using AWS SAM (Serverless Application Model) or AWS CloudFormation. The included `.yaml` files define the necessary AWS resources (Lambda functions, API Gateway endpoints, S3 buckets, IAM roles, etc.).

### Prerequisites

* AWS Account & IAM User with appropriate permissions

* AWS CLI

* AWS SAM CLI

* Java (version specified in `pom.xml`)

* Apache Maven

* Python

### General Deployment Steps

1. Configure the database credentials and other necessary values as environment variables in the `template.yaml` file.

2. Build the Java project using Maven: `mvn clean package`.

3. Deploy the application using the AWS SAM CLI:

```

sam build
sam deploy --guided

```

*This README was generated based on the project's architecture and source code structure.*
```
