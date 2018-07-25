/*
*************************************************************************************
* Copyright 2018 Normation SAS
*************************************************************************************
*
* This file is part of Rudder.
*
* Rudder is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU General Public License version 3, the copyright holders add
* the following Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
* Public License version 3, when you create a Related Module, this
* Related Module is not considered as a part of the work and may be
* distributed under the license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* Rudder is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

*
*************************************************************************************
*/

package com.normation.plugins.changesvalidation

import com.normation.eventlog.EventActor
import com.normation.rudder.domain.workflows._
import net.liftweb.common._
import com.normation.rudder.repository._
import com.normation.rudder.services.eventlog.WorkflowEventLogService
import com.normation.rudder.batch.AsyncWorkflowInfo
import com.normation.rudder.services.marshalling.ChangeRequestChangesSerialisation
import com.normation.rudder.services.workflows.CommitAndDeployChangeRequestService
import com.normation.rudder.services.workflows.NoWorkflowAction
import com.normation.rudder.services.workflows.WorkflowAction
import com.normation.rudder.services.workflows.WorkflowService
import com.normation.rudder.services.workflows.WorkflowUpdate

import scala.xml.NodeSeq

/**
 * A proxy workflow service based on a runtime choice
 */
class EitherWorkflowService(cond: () => Box[Boolean], whenTrue: WorkflowService, whenFalse: WorkflowService) extends WorkflowService {

  //TODO: handle ERRORS for config!

  val name = "choose-active-validation-workflow"

  def current: WorkflowService = if(cond().getOrElse(false)) whenTrue else whenFalse

  def startWorkflow(changeRequestId: ChangeRequestId, actor:EventActor, reason: Option[String]) : Box[WorkflowNodeId] =
    if(cond().getOrElse(false)) whenTrue.startWorkflow(changeRequestId, actor, reason) else whenFalse.startWorkflow(changeRequestId, actor, reason)
  def openSteps :List[WorkflowNodeId] =
    if(cond().getOrElse(false)) whenTrue.openSteps else whenFalse.openSteps
  def closedSteps :List[WorkflowNodeId] =
    if(cond().getOrElse(false)) whenTrue.closedSteps else whenFalse.closedSteps
  def stepsValue :List[WorkflowNodeId] =
    if(cond().getOrElse(false)) whenTrue.stepsValue else whenFalse.stepsValue
  def findNextSteps(currentUserRights: Seq[String], currentStep: WorkflowNodeId, isCreator: Boolean) : WorkflowAction =
    if(cond().getOrElse(false)) whenTrue.findNextSteps(currentUserRights, currentStep, isCreator) else whenFalse.findNextSteps(currentUserRights, currentStep, isCreator)
  def findBackSteps(currentUserRights: Seq[String], currentStep: WorkflowNodeId, isCreator: Boolean) : Seq[(WorkflowNodeId,(ChangeRequestId,EventActor, Option[String]) => Box[WorkflowNodeId])] =
    if(cond().getOrElse(false)) whenTrue.findBackSteps(currentUserRights, currentStep, isCreator) else whenFalse.findBackSteps(currentUserRights, currentStep, isCreator)
  def findStep(changeRequestId: ChangeRequestId) : Box[WorkflowNodeId] =
    if(cond().getOrElse(false)) whenTrue.findStep(changeRequestId) else whenFalse.findStep(changeRequestId)
  def getAllChangeRequestsStep() : Box[Map[ChangeRequestId,WorkflowNodeId]] =
    if(cond().getOrElse(false)) whenTrue.getAllChangeRequestsStep else whenFalse.getAllChangeRequestsStep
  def isEditable(currentUserRights: Seq[String], currentStep: WorkflowNodeId, isCreator: Boolean): Boolean =
    if(cond().getOrElse(false)) whenTrue.isEditable(currentUserRights, currentStep, isCreator) else whenFalse.isEditable(currentUserRights, currentStep, isCreator)
  def isPending(currentStep:WorkflowNodeId): Boolean =
    if(cond().getOrElse(false)) whenTrue.isPending(currentStep) else whenFalse.isPending(currentStep)
}


trait ValidationNeeded {

  // check is the given change request, initiated by actor, need to be validated or can
  // be auto deployed.
  def check(changeRequestId: ChangeRequestId, actor: EventActor): Box[Boolean]
}

class KonamiValidationNeeded(
    roChangeRequestRepository: RoChangeRequestRepository
  , changeRequestChangesSerialisation: ChangeRequestChangesSerialisation
) extends ValidationNeeded {
  val code = "↑↑↓↓←→←→BA"
  override def check(changeRequestId: ChangeRequestId, actor: EventActor): Box[Boolean] = {
    for {
      opt <- roChangeRequestRepository.get(changeRequestId)
    } yield {
      val xml = opt match {
                case Some(cr) => changeRequestChangesSerialisation.serialise(cr)
                case None     => Full(NodeSeq.Empty)
              }
      if(xml.toString().contains(code)) {
        ChangesValidationLogger.info("Cheat code detected, avoid validation!")
        false
      } else {
        ChangesValidationLogger.info("That change request need validation")
        true
      }
    }
  }
}



class TwoValidationStepsWorkflowServiceImpl(
    workflowLogger  : WorkflowEventLogService
  , commit          : CommitAndDeployChangeRequestService
  , roWorkflowRepo  : RoWorkflowRepository
  , woWorkflowRepo  : WoWorkflowRepository
  , workflowComet   : AsyncWorkflowInfo
  , validationNeeded: ValidationNeeded
  , selfValidation  : () => Box[Boolean]
  , selfDeployment  : () => Box[Boolean]
) extends WorkflowService {

  val name = "two-steps-validation-workflow"

  case object Validation extends WorkflowNode {
    val id = WorkflowNodeId("Pending validation")
  }

  case object Deployment extends WorkflowNode {
    val id = WorkflowNodeId("Pending deployment")
  }

  case object Deployed extends WorkflowNode {
    val id = WorkflowNodeId("Deployed")
  }

  case object Cancelled extends WorkflowNode {
    val id = WorkflowNodeId("Cancelled")
  }

  val steps:List[WorkflowNode] = List(Validation,Deployment,Deployed,Cancelled)

  def getItemsInStep(stepId: WorkflowNodeId) : Box[Seq[ChangeRequestId]] = roWorkflowRepo.getAllByState(stepId)

  val openSteps : List[WorkflowNodeId] = List(Validation.id,Deployment.id)
  val closedSteps : List[WorkflowNodeId] = List(Cancelled.id,Deployed.id)
  val stepsValue = steps.map(_.id)

  def findNextSteps(
      currentUserRights : Seq[String]
    , currentStep       : WorkflowNodeId
    , isCreator         : Boolean
  ) : WorkflowAction = {
    val authorizedRoles = currentUserRights.filter(role => (role == "validator" || role == "deployer"))
    //TODO: manage error for config !
    val canValid  = selfValidation().getOrElse(false) || !isCreator
    val canDeploy = selfDeployment().getOrElse(false) || !isCreator
    currentStep match {
      case Validation.id =>
        val validatorActions =
          if (authorizedRoles.contains("validator") && canValid)
            Seq((Deployment.id,stepValidationToDeployment _)) ++ {
            if(authorizedRoles.contains("deployer") && canDeploy)
              Seq((Deployed.id,stepValidationToDeployed _))
              else Seq()
             }
          else Seq()
        WorkflowAction("Validate",validatorActions )


      case Deployment.id =>
        val actions =
          if(authorizedRoles.contains("deployer") && canDeploy)
            Seq((Deployed.id,stepDeploymentToDeployed _))
          else Seq()
        WorkflowAction("Deploy",actions)
      case Deployed.id   => NoWorkflowAction
      case Cancelled.id  => NoWorkflowAction
      case WorkflowNodeId(x) =>
        ChangesValidationLogger.warn(s"An unknow workflow state was reached with ID: '${x}'. It is likely to be a bug, please report it")
        NoWorkflowAction
    }
  }

  def findBackSteps(
      currentUserRights : Seq[String]
    , currentStep       : WorkflowNodeId
    , isCreator         : Boolean
  ) : Seq[(WorkflowNodeId,(ChangeRequestId,EventActor, Option[String]) => Box[WorkflowNodeId])] = {
    val authorizedRoles = currentUserRights.filter(role => (role == "validator" || role == "deployer"))
    //TODO: manage error for config !
    val canValid  = selfValidation().getOrElse(false) || !isCreator
    val canDeploy = selfDeployment().getOrElse(false) || !isCreator
    currentStep match {
      case Validation.id =>
        if (authorizedRoles.contains("validator") && canValid) Seq((Cancelled.id,stepValidationToCancelled _)) else Seq()
      case Deployment.id => if (authorizedRoles.contains("deployer") && canDeploy)  Seq((Cancelled.id,stepDeploymentToCancelled _)) else Seq()
      case Deployed.id   => Seq()
      case Cancelled.id  => Seq()
      case WorkflowNodeId(x) =>
        ChangesValidationLogger.warn(s"An unknow workflow state was reached with ID: '${x}'. It is likely to be a bug, please report it")
        Seq()
    }
  }

  def isEditable(currentUserRights:Seq[String],currentStep:WorkflowNodeId, isCreator : Boolean): Boolean = {
    val authorizedRoles = currentUserRights.filter(role => (role == "validator" || role == "deployer"))
    currentStep match {
      case Validation.id => authorizedRoles.contains("validator") || isCreator
      case Deployment.id => authorizedRoles.contains("deployer")
      case Deployed.id   => false
      case Cancelled.id  => false
      case WorkflowNodeId(x) =>
        ChangesValidationLogger.warn(s"An unknow workflow state was reached with ID: '${x}'. It is likely to be a bug, please report it")
        false
    }
  }

  def isPending(currentStep:WorkflowNodeId): Boolean = {
    currentStep match {
      case Validation.id => true
      case Deployment.id => true
      case Deployed.id   => false
      case Cancelled.id  => false
      case WorkflowNodeId(x) =>
        ChangesValidationLogger.warn(s"An unknow workflow state was reached with ID: '${x}'. It is likely to be a bug, please report it")
        false
    }
  }
  def findStep(changeRequestId: ChangeRequestId) : Box[WorkflowNodeId] = {
    roWorkflowRepo.getStateOfChangeRequest(changeRequestId)
  }


  def getAllChangeRequestsStep : Box[Map[ChangeRequestId,WorkflowNodeId]] = {
    roWorkflowRepo.getAllChangeRequestsState
  }

  private[this] def changeStep(
      from           : WorkflowNode
    , to             : WorkflowNode
    , changeRequestId: ChangeRequestId
    , actor          : EventActor
    , reason         : Option[String]
  ) : Box[WorkflowNodeId] = {
    (for {
      state <- woWorkflowRepo.updateState(changeRequestId,from.id, to.id)
      workflowStep = WorkflowStepChange(changeRequestId,from.id,to.id)
      log   <- workflowLogger.saveEventLog(workflowStep,actor,reason)
    } yield {
      workflowComet ! WorkflowUpdate
      state
    }) match {
      case Full(state) => Full(state)
      case e:Failure => ChangesValidationLogger.error(s"Error when changing step in workflow for Change Request ${changeRequestId.value} : ${e.msg}")
                        e
      case Empty => ChangesValidationLogger.error(s"Error when changing step in workflow for Change Request ${changeRequestId.value} : no reason given")
                    Empty
    }
  }

  private[this] def toFailure(from: WorkflowNode, changeRequestId: ChangeRequestId, actor: EventActor, reason: Option[String]) : Box[WorkflowNodeId] = {
    changeStep(from, Cancelled,changeRequestId,actor,reason)
  }

  def startWorkflow(changeRequestId: ChangeRequestId, actor:EventActor, reason: Option[String]) : Box[WorkflowNodeId] = {
    for {
      shouldValidate <- validationNeeded.check(changeRequestId, actor)
      started        <- if(shouldValidate) {
                          startTwoStepWorkflow(changeRequestId, actor, reason)
                        } else {
                          startAutodeployWorkflow(changeRequestId, actor, reason)
                        }
    } yield {
      started
    }
  }

  private[this] def startTwoStepWorkflow(changeRequestId: ChangeRequestId, actor:EventActor, reason: Option[String]) : Box[WorkflowNodeId] = {
    ChangesValidationLogger.debug(s"${name}: start workflow for change request '${changeRequestId.value}'")
    for {
      workflow <- woWorkflowRepo.createWorkflow(changeRequestId, Validation.id)
    } yield {
      workflowComet ! WorkflowUpdate
      workflow
    }
  }

  private[this] def startAutodeployWorkflow(changeRequestId: ChangeRequestId, actor:EventActor, reason: Option[String]) : Box[WorkflowNodeId] = {
    ChangesValidationLogger.debug(s"${name}: automatically deploy changes for change request '${changeRequestId.value}'")
    for {
      result <- commit.save(changeRequestId, actor, reason)
    } yield {
      // and return a no workflow
      Deployed.id
    }
  }

  private[this] def onSuccessWorkflow(from: WorkflowNode, changeRequestId: ChangeRequestId, actor:EventActor, reason: Option[String]) : Box[WorkflowNodeId] = {
    ChangesValidationLogger.debug(s"${name}: deploy changes for change request '${changeRequestId.value}'")
    for {
      save  <- commit.save(changeRequestId, actor, reason)
      state <- changeStep(from,Deployed,changeRequestId,actor,reason)
    } yield {
      state
    }

  }

  //allowed workflow steps


  private[this] def stepValidationToDeployment(changeRequestId:ChangeRequestId, actor:EventActor, reason: Option[String]) : Box[WorkflowNodeId] = {
    changeStep(Validation, Deployment,changeRequestId, actor, reason)
  }


  private[this] def stepValidationToDeployed(changeRequestId:ChangeRequestId, actor:EventActor, reason: Option[String]) : Box[WorkflowNodeId] = {
    onSuccessWorkflow(Validation, changeRequestId, actor, reason)
  }

  private[this] def stepValidationToCancelled(changeRequestId:ChangeRequestId, actor:EventActor, reason: Option[String]) : Box[WorkflowNodeId] = {
    toFailure(Validation, changeRequestId, actor, reason)
  }

  private[this] def stepDeploymentToDeployed(changeRequestId:ChangeRequestId, actor:EventActor, reason: Option[String]) : Box[WorkflowNodeId] = {
    onSuccessWorkflow(Deployment, changeRequestId, actor, reason)
  }


  private[this] def stepDeploymentToCancelled(changeRequestId:ChangeRequestId, actor:EventActor, reason: Option[String]) : Box[WorkflowNodeId] = {
    toFailure(Deployment, changeRequestId, actor, reason)
  }


}


