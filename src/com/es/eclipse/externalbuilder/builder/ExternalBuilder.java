package com.es.eclipse.externalbuilder.builder;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IResourceDeltaVisitor;
import org.eclipse.core.resources.IResourceVisitor;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Objects;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.Lists;
import com.google.common.io.ByteStreams;

public class ExternalBuilder extends IncrementalProjectBuilder {

    static class ResourceListBuilder implements IResourceDeltaVisitor, IResourceVisitor {

        List<IResource> mResources = Lists.newLinkedList();
        /*
         * (non-Javadoc)
         * 
         * @see org.eclipse.core.resources.IResourceDeltaVisitor#visit(org.eclipse.core.resources.IResourceDelta)
         */
        public boolean visit(IResourceDelta delta) throws CoreException {
            IResource resource = delta.getResource();
            switch (delta.getKind()) {
            case IResourceDelta.ADDED:
            case IResourceDelta.CHANGED:
                mResources.add(resource);
                break;

            case IResourceDelta.REMOVED:
                //We don't care removed items here.
                break;
            }
            //return true to continue visiting children.
            return true;
        }


        public boolean visit(IResource resource) {
            mResources.add(resource);
            //return true to continue visiting children.
            return true;
        }

        /**
         * @return the mResources
         */
        public List<IResource> getResourceList() {
            return mResources;
        }
    }

    class ErrorReporter extends DefaultHandler {
        String text;
        String fileName;
        IFile file;
        int lineNo;
        String message;

        @Override
        public void characters(char[] ch, int start, int length ){
            text = new String( ch, start, length );
        }

        @Override
        public void endElement(String uri, String localName, String qName ){
            if( qName.equals( "message" ) ){
                message = text;
            }
            else if( qName.equals( "file" ) ){
                if( !text.equals(fileName) ){
                    fileName = text;
                    file = ExternalBuilder.this.getFile(fileName);
                }
            }
            else if( qName.equals( "line" ) ){
                lineNo = Integer.parseInt(text);
            }
            else if( qName.equals( "error" ) ){
                ExternalBuilder.this.addMarker(file, message, lineNo );
            }
        }

    }

    ErrorReporter reporter = new ErrorReporter();

    void parseErrorStream( InputStream in ){
        try {
            getParser().parse(in,reporter );
        } catch (Exception e1) {
            throw new RuntimeException(e1);
        }
    }

	private SAXParserFactory parserFactory;

	private SAXParser getParser() throws ParserConfigurationException,
			SAXException {
		if (parserFactory == null) {
			parserFactory = SAXParserFactory.newInstance();
		}
		return parserFactory.newSAXParser();
	}

    public static final String BUILDER_ID = "com.es.eclipse.externalbuilder.externalBuilder";

    private static final String MARKER_TYPE = "com.es.eclipse.externalbuilder.xmlProblem";

    private void addMarker(IFile file, String message, int lineNumber) {
        try {
            IMarker marker = file.createMarker(MARKER_TYPE);
            marker.setAttribute(IMarker.MESSAGE, message);
            marker.setAttribute(IMarker.SEVERITY, IMarker.SEVERITY_ERROR);
            if (lineNumber == -1) {
                lineNumber = 1;
            }
            marker.setAttribute(IMarker.LINE_NUMBER, lineNumber);
        } catch (CoreException e) {
        }
    }

    Map<String,IFile> mFileMap;

    static class ExternalBuilderOptions{
        private IPath mProjectPath;
        private IPath mWorkspacePath;
        private IPath mOutputPath;

        List<IPath> mClasspathes = Lists.newLinkedList();
        List<IFile> mResources = Lists.newLinkedList();

        String mProjectFolderName;

        ExternalBuilderOptions( IPath workspace_path, IPath project_path ){
            mWorkspacePath = workspace_path;
            setProjectPath( project_path );
        }

        private void setProjectPath( IPath path ){
            mProjectPath = path;
            mProjectFolderName = mProjectPath.lastSegment(); 
        }

        IPath getProjectPath(){
            return mProjectPath;
        }

        IPath getWorkspacePath(){
            return mWorkspacePath;
        }

        void setOutputPath(IPath output_path ){
            mOutputPath = makeAbsolute( output_path );
        }

        IPath getOutputPath(){
            return mOutputPath;
        }

        IPath makeAbsolute( IPath path ){
            if( Objects.equal( path.segment(0), mProjectFolderName ) ){
                return mWorkspacePath.append( path );
            }
            return path;
        }

        void addClasspath( IPath classpath ){
            mClasspathes.add( makeAbsolute(classpath) );
        }

        String toXml(){
            SimpleXmlWriter xml = SimpleXmlWriter.newInstance();

            xml.element("builder-options").startChildren();
            xml.element("workspace-path").text( mWorkspacePath.toOSString() );
            xml.element("project-path").text( mProjectPath.toOSString() );
            xml.element("output-path").text( mOutputPath.toOSString() );
            for( IPath classpath : mClasspathes ){
                xml.element( "classpath" ).text( classpath.toOSString() );
            }
            for( IFile resource : mResources ){
                System.out.println( resource.getFullPath().toOSString() );
                xml.element( "resource" ).text( makeAbsolute( resource.getFullPath() ).toOSString() );
            }
            xml.endChildren();
            return xml.toXml();
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.eclipse.core.internal.events.InternalBuilder#build(int,
     *      java.util.Map, org.eclipse.core.runtime.IProgressMonitor)
     */
    protected IProject[] build(int kind, Map<String,String> args, IProgressMonitor monitor) throws CoreException {
        final ExternalBuilderOptions build_opts = new ExternalBuilderOptions( getProject().getWorkspace().getRoot().getLocation(), getProject().getLocation());

        if( kind == FULL_BUILD ){
            System.out.println( "Full build" );
        }
        else if( kind == CLEAN_BUILD ){
            System.out.println( "Clean build" );
            //TODO don't know how to handle clean build
            throw new RuntimeException( "Meet clean build" );
        }
        else{
            System.out.println( "Increment build" );
            if( getDelta(getProject() ) == null){
                System.out.println( "No delta specified" );
                throw new RuntimeException( "Increment build without delta" );
            }
        }

        IJavaProject project = JavaCore.create(getProject());
        if( project == null ){
            System.out.println( "Not a java project!" );
            return null;
        }


        for( IClasspathEntry classpath_entry : project.getResolvedClasspath(true) ){
            switch( classpath_entry.getEntryKind() ){
                case IClasspathEntry.CPE_LIBRARY:
                    build_opts.addClasspath(classpath_entry.getPath());
                    break;

                default:
                    //We don't care about other path.
                    //TODO need to handle CPE_PROJECT????
            }
        }

        build_opts.setOutputPath( project.getOutputLocation() );

        List<IResource> resources = getResourceList( kind == FULL_BUILD ? null : getDelta(getProject() ) );

        for( IResource resource :resources ){
            if (resource instanceof IFile && resource.getName().endsWith(".java")) {
                IFile file = (IFile) resource;
                deleteMarkers(file);
                build_opts.mResources.add( file );
            }
        }


        mFileMap = FluentIterable.from(build_opts.mResources).uniqueIndex( new Function<IFile,String>(){
            @Override
            public String apply(IFile file){
                return build_opts.makeAbsolute( file.getFullPath() ).toOSString();
            }

            @Override
            public boolean equals(Object object){
                return false;
            }
        });

        launchExternalBuilder( build_opts );

        return null;
    }

    IFile getFile(String name){
        return mFileMap.get(name);
    }

    void launchExternalBuilder( ExternalBuilderOptions build_opts ){
        System.out.print( build_opts.toXml() );

        try{
            Process p = new ProcessBuilder( "/home/w19816/personal/eclipse-openjml/openjml-builder" ).start();

            BufferedWriter writer = new BufferedWriter( new OutputStreamWriter( p.getOutputStream(), Charsets.UTF_8 ) );
            writer.write( build_opts.toXml() );
            writer.close();

            //TODO need multithread or multixed it
            parseErrorStream( p.getErrorStream() ); 
            ByteStreams.copy( p.getInputStream(), System.out );
            //ByteStreams.copy( p.getErrorStream(), System.out );

            try{
                p.waitFor();
            }
            catch(InterruptedException e){
                //TODO ignore interruptedException here
            }

            p.destroy();
        }
        catch(IOException e){
            throw new RuntimeException(e);
        }

    }

    private void deleteMarkers(IFile file) {
        try {
            file.deleteMarkers(MARKER_TYPE, false, IResource.DEPTH_ZERO);
        } catch (CoreException ce) {
        }
    }

    protected List<IResource> getResourceList(IResourceDelta delta)
            throws CoreException {
        ResourceListBuilder visitor = new ResourceListBuilder();
        try {
            if( delta == null ){
                getProject().accept(visitor);
            }
            else{
                delta.accept(visitor);
            }
        } catch (CoreException e) {
            //TODO: need to log here
        }

        return visitor.getResourceList();
    }
}
